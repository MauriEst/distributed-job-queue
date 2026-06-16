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

### 5. Exponential Backoff & Retry Routing
To protect downstream systems from "thundering herd" scenarios during outages, failed jobs are not retried immediately. The engine calculates a dynamic backoff delay using the formula `base_delay * (2 ^ attempt)`. This gives struggling external APIs time to recover before the worker pool attempts to process the payload again.

### 6. Time-Delayed Execution
The queue supports future scheduling. Clients can submit jobs with an `executeAt` ISO-8601 timestamp. The worker's leasing query (`execute_at <= CURRENT_TIMESTAMP`) ensures these rows remain invisible to the active worker pool until the exact millisecond they ripen, requiring zero background cron polling.

### 7. Dynamic Task Routing (Strategy Pattern)
To adhere to the Open-Closed Principle, the core execution loop is entirely decoupled from business logic. A `TaskHandlerRegistry` dynamically routes JSON payloads to specific `TaskHandler` beans at runtime based on the `taskType` string. Adding a new background task (like Image Processing, AI summarize) requires zero modifications to the core worker engine.

---

## Technical Stack & Tools

* **Runtime**: Java 21 (Leveraging Project Loom Virtual Threads)
* **Framework**: Spring Boot 3.2.x (Spring Data JPA, Redis Core)
* **Database**: PostgreSQL 15+ (Row-Level Locking, Advanced Indexing)
* **Cache/Broker**: Redis 7 (Pub/Sub Event Signaling)
* **Architecture**: Maven Multi-Module (Core, API, Worker)
* **Orchestration**: Docker Compose & Kubernetes (K8s Manifests included)
* **CI/CD**: GitHub Actions (Automated build and test pipelines)

---

### Local Stress Testing & Verification

With Docker Compose, you can instantly spin up the entire distributed cluster on your local machine. The compose file is pre-configured to launch multiple isolated worker containers to prove out the system's distributed locking and concurrency mechanics.

### 1. Boot the Cluster
Make sure your Docker daemon is running, then execute:
```bash
docker-compose up --build -d
```
This will spin up PostgreSQL, Redis, the API gateway, and two independent Worker nodes.

### 2. Flood the Ingestion Pipeline
Execute this parallel shell loop to hit the endpoint with 200 concurrent tasks fractions of a second apart:

```bash
# Example: Inject 200 immediate tasks
for i in {1..200}; do
  curl -s -X POST http://localhost:8080/jobs \
    -H "Content-Type: application/json" \
    --data-raw "{\"taskType\": \"SEND_EMAIL\", \"payload\": \"{\\\"job_index\\\": $i}\", \"maxRetries\": 3}" > /dev/null &
done
wait
echo "Successfully injected 200 concurrent tasks"
```

### 3. Observe System Behavior
Watch the logs of your multi-node worker pool. You will see both containers waking up instantly via the Redis signal, safely leasing jobs via Postgres row-locks, and processing the queue with zero ID collisions.
```bash
# View aggregated logs from both workers
docker logs -f jq_worker
```
Verify the final data states inside the Postgres database:
```bash
docker exec -it jq_postgres psql -U queue_user -d job_queue -c "SELECT status, COUNT(*) FROM jobs GROUP BY status;"
```

## Production Orchestration
For production environments, the system is designed to be deployed onto a Kubernetes cluster. Standard K8s manifests are provided in the /k8s directory.

To deploy the architecture to a cluster (e.g., Minikube, EKS, GKE):
```bash
kubectl apply -f k8s/postgres.yaml
kubectl apply -f k8s/redis.yaml
kubectl apply -f k8s/api.yaml
kubectl apply -f k8s/worker.yaml
```