package com.queue.worker;

import com.queue.core.models.JobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class WorkerEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkerEngine.class);

    private final JobTransactionService txService; // transaction layer proxy
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    public WorkerEngine(JobTransactionService txService) {
        this.txService = txService;
    }

    @Scheduled(fixedDelay = 30000) // Fallback sanity sweep
    public void scheduledFallbackSweep() {
        log.info("Firing 30-second fallback database sanity sweep...");
        executeDrainLoop();
    }

    public void pollForJobs(String message) {
        log.info("Redis event signal received. Waking up worker engine context...");
        executeDrainLoop();
    }

    /**
     * Implements the drain loop. Once woken up, the worker
     * will continuously strip jobs until the DB yields empty snapshots.
     */
    private void executeDrainLoop() {
        boolean continuousCheck = true;

        while (continuousCheck) {
            // Invoking across the proxy bean interface—REQUIRES_NEW transaction
            Optional<UUID> claimedJobId = txService.claimNextJobAtomically(workerId);

            if (claimedJobId.isPresent()) {
                UUID jobId = claimedJobId.get();
                log.info("[{}] Safely committed claim for Job {}. Dispatching to Virtual Thread...", workerId, jobId);
                executor.submit(() -> executeTask(jobId));
            } else {
                continuousCheck = false; // Queue fully drained. Sleep until next event signal.
            }
        }
    }

    private void executeTask(UUID jobId) {
        try {
            log.info("Starting task execution context for Job {}", jobId);

            // Simulate a heavy 8-second processing workload, beating the heart every 2 seconds
            for (int i = 0; i < 4; i++) {
                Thread.sleep(2000);
                txService.sendHeartbeat(jobId);
            }

            txService.updateJobStatus(jobId, JobStatus.COMPLETED, null);
            log.info("Finished execution successfully for job {}", jobId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            txService.updateJobStatus(jobId, JobStatus.FAILED, "Task execution interrupted via runtime signal.");
        } catch (Exception e) {
            txService.updateJobStatus(jobId, JobStatus.FAILED, e.getMessage());
        }
    }

    /**
     * Guarantees that if the application or its container context shuts down,
     * the active virtual threads are allowed to clean up gracefully instead of hard snapping.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down worker execution infrastructure pool gracefully...");
        executor.shutdown();
    }
}