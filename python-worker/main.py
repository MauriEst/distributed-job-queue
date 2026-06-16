import os
import time
import json
import uuid
import psycopg2
import redis
from datetime import datetime, timezone

# 1. Configuration from Environment Variables (Matches our Java setup!)
DB_HOST = os.getenv("DB_HOST", "localhost")
REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
WORKER_ID = f"py-worker-{str(uuid.uuid4())[:8]}"
SUPPORTED_TASK = "AI_SUMMARIZE"

print(f"[{WORKER_ID}] Booting Python AI Worker...")

# 2. Connect to backing services
r = redis.Redis(host=REDIS_HOST, port=6379, decode_responses=True)
conn = psycopg2.connect(
    dbname="job_queue",
    user="queue_user",
    password="queue_password",
    host=DB_HOST,
    port=5432
)
conn.autocommit = True # We will handle transactions manually

def process_ai_job(job_id, payload):
    print(f"[{WORKER_ID}] Starting AI generation for Job {job_id}...")
    print(f"[{WORKER_ID}] Payload: {payload}")

    # SIMULATING AN LLM CALL (e.g., passing payload to OpenAI/Langchain)
    time.sleep(10)

    # Mark as completed
    with conn.cursor() as cur:
        cur.execute(
            "UPDATE jobs SET status = 'COMPLETED' WHERE id = %s",
            (job_id,)
        )
    print(f"[{WORKER_ID}] Successfully completed AI job {job_id}")

def poll_database():
    with conn.cursor() as cur:
        # Notice we specifically ask for task_type = 'AI_SUMMARIZE'
        cur.execute("""
            SELECT id, payload FROM jobs
            WHERE status = 'PENDING' 
              AND execute_at <= CURRENT_TIMESTAMP
              AND task_type = %s
            ORDER BY execute_at ASC 
            LIMIT 1
            FOR UPDATE SKIP LOCKED
        """, (SUPPORTED_TASK,))

        job = cur.fetchone()

        if job:
            job_id, payload = job
            # Claim the job
            cur.execute(
                "UPDATE jobs SET status = 'PROCESSING', assigned_worker_id = %s WHERE id = %s",
                (WORKER_ID, job_id)
            )
            process_ai_job(job_id, payload)
            return True
        return False

# 3. The Event Loop (Listen to Redis, just like Java)
pubsub = r.pubsub()
pubsub.subscribe("job_channel")

print(f"[{WORKER_ID}] Listening for jobs on Redis channel 'job_channel'...")

while True:
    message = pubsub.get_message(ignore_subscribe_messages=True, timeout=5.0)

    if message:
        print(f"[{WORKER_ID}] Redis signal received. Waking up...")
        # Drain the queue until empty
        while poll_database():
            pass