package com.queue.worker;

import com.queue.core.models.Job;
import com.queue.core.models.JobStatus;
import com.queue.core.repositories.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class WorkerEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkerEngine.class);
    private final JobRepository jobRepository;

    // Create an ExecutorService backed entirely by Java 21 Virtual Threads
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

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
            jobRepository.saveAndFlush(job);

            log.info("Successfully claimed Job {} [Type: {}]. Handing off to Virtual Thread...", job.getId(), job.getTaskType());

            // Send execution to virtual thread
            executor.submit(() -> executeTask(job));
        }
    }

    private void executeTask(Job job) {
        try {
            log.info("Starting processing execution for Job {}", job.getId());

            // Mocking a heavy workload execution
            Thread.sleep(5000);

            // Update database state to complete
            updateJobStatus(job.getId(), JobStatus.COMPLETED, null);
            log.info("Finished execution successfully for job {}", job.getId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            updateJobStatus(job.getId(), JobStatus.FAILED, "Task execution interrupted");
        } catch (Exception e) {
            updateJobStatus(job.getId(), JobStatus.FAILED, e.getMessage());
        }
    }

    private void updateJobStatus(java.util.UUID jobId, JobStatus finalStatus, String errorMessage) {
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
