# High-Throughput Distributed Job Queue

A highly scalable, fault-tolerant, event-driven asynchronous task execution engine built with **Java 21 (Virtual Threads)**, **Spring Boot**, **PostgreSQL**, and **Redis**.

This system separates high-volume ingestion from heavy task processing using a decoupled multi-process microservices architecture, solving core distributed systems problems like race conditions, worker failure recovery, and resource starvation.

---

## Architectural Overview

The system is split into three decoupled Maven modules to cleanly separate core domain data layers from active execution runtimes:
* **`core`**: Contains shared JPA entities, PostgreSQL repositories, and database schemas.
* **`api`**: A lightweight web gateway designed purely for sub-millisecond job ingestion and status tracking.
* **`worker`**: An independent execution engine that coordinates background resource leasing, virtual thread pooling, and automated error mitigation.



### The End-to-End Lifecycle
1.  **Ingestion**: The client submits a task payload via `POST /jobs`. The **API Gateway** writes the transaction to PostgreSQL with a `PENDING` status and instantly broadcasts the generated Job UUID over a **Redis Pub/Sub** channel (`job_channel`), returning a `202 Accepted` response.
2.  **Signaling & Waking**: Sleepy **Worker Nodes** listen to the Redis event channel. The millisecond a signal is received, the worker wakes up and triggers a high-performance database drain loop.
3.  **Atomic Concurrency Control**: Workers query the database to lease work. Multiple workers are prevented from duplicate execution using a database row-level locking strategy.
4.  **Virtual Thread Offloading**: Once an active Row ID is securely leased, the worker engine hands execution off to a Java 21 `VirtualThreadPerTaskExecutor` pool to handle long-running operations without consuming OS-level platform threads.

---

## Key Distributed Systems Mechanisms Implemented

### 1. Concurrency Control (`SELECT ... FOR UPDATE SKIP LOCKED`)
To prevent distributed race conditions when scaling horizontally to multiple worker application processes, the engine utilizes a pessimistic row-locking strategy.
By utilizing a native PostgreSQL signature, worker nodes can query, lock, and mark rows as `PROCESSING` atomically. Workers competing for work at the exact same millisecond will seamlessly skip past locked rows without blocking, enabling smooth, linear horizontal scaling.

### 2. Isolated Transaction Demarcation (Spring Proxy Bypass Fix)
To prevent stale snapshots and ensure state transitions are globally visible before asynchronous work starts, the worker isolates data leasing from task execution. Transaction components are broken out into a separate proxy-backed bean (`JobTransactionService`) using `Propagation.REQUIRES_NEW`. This guarantees that a job lease is fully committed to the disk before the processing thread spins up.

### 3. Fault Tolerance & Dead-Letter Isolation (The Janitor Sweep)
If a worker node crashes mid-task, the active job risks becoming a permanent "zombie job" stuck in a processing state.
* **Active Heartbeats**: While a virtual thread executes a task, it updates a `last_heartbeat_at` timestamp in the database every 2 seconds.
* **The Recovery Janitor**: A background supervisor loop scans the table every 5 seconds. If it catches a job in the `PROCESSING` state whose heartbeat is older than 10 seconds, it evicts the dead worker, increments a `retries_count`, and places the job back into `PENDING` status.
* **Poison Pill Protection**: If a job repeatedly crashes and exhausts its configured `max_retries`, the Janitor automatically routes it to a `FAILED` status with a saved `error_message` log to protect the health of the system.

### 4. Event-Driven Performance via Redis Caching
Rather than forcing background workers to spam the relational database with endless poll queries during idle hours, the architecture leverages Redis as an event broker. Workers remain silent until an in-memory alert wakes them up, dropping resource consumption on the database to zero when the queue is dry.

---

## Technical Stack & Tools

* **Runtime**: Java 21 (Leveraging Project Loom Virtual Threads)
* **Framework**: Spring Boot 3.x (Spring Data JPA, Redis Core)
* **Database**: PostgreSQL 15+ (Row-Level Locking, Advanced Indexing)
* **Cache/Broker**: Redis 7 (Pub/Sub Event Signaling)
* **Build System**: Maven (Multi-Module Project Architecture)
* **Containerization**: Docker Compose

---

## Local Stress Testing & Verification

The queue's resilience can be verified locally by spinning up multiple worker nodes and hammering the API with high concurrent traffic.

### 1. Spin up the Core Infrastructure
```bash
docker-compose up -d
mvn clean install
```

### 2. Boot Multiple Parallel Worker Processes
Configure IntelliJ to allow parallel run configurations of `WorkerApplication`. Set your `server.port=0` inside `application.yml` to automatically allocate unique dynamic ports, then boot 3-4 separate worker instances.

### 3. Flood the Ingestion Pipeline
Execute this parallel shell loop to hit the endpoint with 200 concurrent tasks fractions of a second apart:

```bash
for i in {1..200}; do
  curl -s -X POST http://localhost:8080/jobs \
    -H "Content-Type: application/json" \
    --data-raw "{\"taskType\": \"{task_example}\", \"payload\": \"{\\\"job_index\\\": $i}\", \"maxRetries\": 3}" > /dev/null &
done
wait
echo "Successfully injected 200 concurrent tasks"
```

### 4. Observe System Behavior
* Review the distinct worker logs to watch them perfectly slice and divide the 200 tasks in real-time with zero ID collisions.
* Verify data states inside the Postgres Query window:
```sql
SELECT status, COUNT(*) FROM jobs GROUP BY status;
```