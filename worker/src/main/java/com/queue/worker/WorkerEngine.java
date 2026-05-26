package com.queue.worker;

import com.queue.core.models.Job;
import com.queue.core.models.JobStatus;
import com.queue.core.repositories.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class WorkerEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkerEngine.class);
    private final JobRepository jobRepository;

    // Create an ExecutorService backed entirely by Java 21 Virtual Threads
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    // Generate a unique identifier for this specific worker node process
    private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    public WorkerEngine(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void pollForJobs() {
        Optional<Job> availableJobs = jobRepository.findNextAvailableJobForUpdate();

        if (availableJobs.isPresent()) {
            Job job = availableJobs.get();

            // Instant transition status to PROCESSING within transaction lock
            job.setStatus(JobStatus.PROCESSING);
            job.setAssignedWorkerID(workerId);
            job.setLastHeartbeatAt(OffsetDateTime.now());
            jobRepository.saveAndFlush(job);

            log.info("[{}] successfully claimed Job {} [Type: {}]. Handing off to Virtual Thread...", workerId, job.getId(), job.getTaskType());

            // Send execution to virtual thread
            executor.submit(() -> executeTask(job.getId()));
        }
    }

    private void executeTask(UUID jobId) {
        try {
            log.info("Starting processing execution for Job {}", jobId);

            // Simulate a heavy 8-second processing workload, beating the heart every 2 seconds
            for (int i = 0; i < 4; i++) {
                Thread.sleep(2000);
                sendHeartbeat(jobId);
            }

            // Update database state to complete
            updateJobStatus(jobId, JobStatus.COMPLETED, null);
            log.info("Finished execution successfully for job {}", jobId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            updateJobStatus(jobId, JobStatus.FAILED, "Task execution interrupted");
        } catch (Exception e) {
            updateJobStatus(jobId, JobStatus.FAILED, e.getMessage());
        }
    }

    private void sendHeartbeat(UUID jobId) {
        try {
            Job job = jobRepository.findById(jobId).orElseThrow();
            if (job.getStatus() == JobStatus.PROCESSING) {
                job.setLastHeartbeatAt(OffsetDateTime.now());
                jobRepository.save(job);
                log.debug("Heartbeat kicked for Job {}", jobId);
            }
        } catch (Exception e) {
            log.error("Failed to send heartbeat for job {}: {}", jobId, e.getMessage());
        }
    }

    private void updateJobStatus(UUID jobId, JobStatus finalStatus, String errorMessage) {
        // Open a clean isolation context to write completion state
        Job job = jobRepository.findById(jobId).orElseThrow();
        job.setStatus(finalStatus);
        if (errorMessage != null) {
            // Truncate message if necessary to fit columns
            job.setPayload("{\"error\": \"" + errorMessage + "\"}");
        }
        jobRepository.save(job);
    }
}
