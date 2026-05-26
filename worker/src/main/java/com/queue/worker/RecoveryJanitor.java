package com.queue.worker;

import com.queue.core.models.Job;
import com.queue.core.models.JobStatus;
import com.queue.core.repositories.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Component
public class RecoveryJanitor {

    private static final Logger log = LoggerFactory.getLogger(RecoveryJanitor.class);
    private final JobRepository jobRepository;

    public RecoveryJanitor(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    // Runs every 5 seconds to clear out dead nodes
    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void sweepForZombieJobs() {
        // A job is considered dead if its heartbeat hasn't checked in for over 10 seconds
        OffsetDateTime timeoutThreshold = OffsetDateTime.now().minusSeconds(10);

        List<Job> zombieJobs = jobRepository.findByStatusAndLastHeartbeatAtBefore(JobStatus.PROCESSING, timeoutThreshold);

        for (Job job : zombieJobs) {
            log.warn("DETECTED ZOMBIE JOB: {} was abandoned by worker {}. Evicting...",
                    job.getId(), job.getAssignedWorkerID());

            if (job.getRetriesCount() < job.getMaxRetries()) {
                // Increment retry attempts and put it back out to the queue market
                job.setRetriesCount(job.getRetriesCount() + 1);
                job.setStatus(JobStatus.PENDING);
                job.setAssignedWorkerID(null);
                job.setLastHeartbeatAt(null);
                jobRepository.save(job);
                log.info("Job {} re-queued successfully. Attempt {}/{}",
                        job.getId(), job.getRetriesCount(), job.getMaxRetries());
            } else {
                // Max retries exhausted. Kill the task completely to avoid poison loops
                job.setStatus(JobStatus.FAILED);
                job.setPayload("{\"error\": \"Max retries exhausted via system crash isolation.\"}");
                jobRepository.save(job);
                log.error("Dead Job {} permanently failed. Max retry threshold reached.", job.getId());
            }
        }
    }
}
