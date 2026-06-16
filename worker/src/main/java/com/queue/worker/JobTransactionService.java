package com.queue.worker;

import com.queue.core.models.Job;
import com.queue.core.models.JobStatus;
import com.queue.core.repositories.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobTransactionService {

    private static final Logger log = LoggerFactory.getLogger(JobTransactionService.class);
    private final JobRepository jobRepository;

    public JobTransactionService(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Transactional
    public Optional<UUID> claimNextJobAtomically(String workerId, List<String> supportedTaskTypes) {
        Optional<Job> availableJob = jobRepository.findNextAvailableJobForUpdate(supportedTaskTypes);

        if (availableJob.isPresent()) {
            Job job = availableJob.get();

            job.setStatus(JobStatus.PROCESSING);
            job.setAssignedWorkerID(workerId);
            job.setLastHeartbeatAt(OffsetDateTime.now());

            Job savedJob = jobRepository.saveAndFlush(job);
            return Optional.of(savedJob.getId());
        }
        return Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendHeartbeat(UUID jobId) {
        try {
            jobRepository.findById(jobId).ifPresent(job -> {
                if (job.getStatus() == JobStatus.PROCESSING) {
                    job.setLastHeartbeatAt(OffsetDateTime.now());
                    jobRepository.saveAndFlush(job);
                }
            });
        } catch (Exception e) {
            log.error("Failed to send heartbeat for job {}: {}", jobId, e.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateJobStatus(UUID jobId, JobStatus finalStatus, String errorMessage) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(finalStatus);
            if (errorMessage != null) {
                job.setErrorMessage(errorMessage); // Saved into its own database column
            }
            jobRepository.saveAndFlush(job);
        });
    }

    public void handleJobFailure(UUID jobId, String errorMessage) {
       jobRepository.findById(jobId).ifPresent(job -> {
           if (job.getRetriesCount() < job.getMaxRetries()) {
               job.setRetriesCount(job.getRetriesCount() + 1);

               // Exponential backoff retries formula: base delay * (2 ^ attempt)
               long backOffSeconds = 15 * (long) Math.pow(2, job.getRetriesCount());

               job.setExecuteAt(OffsetDateTime.now().plusSeconds(backOffSeconds));
               job.setStatus(JobStatus.PENDING);
               job.setAssignedWorkerID(null);

               log.warn("Job {} failed (Attempt {}/{}). Retrying in {} seconds. Error: {}",
                       jobId, job.getRetriesCount(), job.getMaxRetries(), backOffSeconds, errorMessage);
           } else {
               job.setStatus(JobStatus.FAILED);
               job.setErrorMessage(errorMessage);
               log.error("Job {} permanently failed after {} attempts. Error: {}",
                       jobId, job.getMaxRetries(), errorMessage);
           }

           jobRepository.saveAndFlush(job);
       });
    }
}