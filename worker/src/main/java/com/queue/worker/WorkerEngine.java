package com.queue.worker;

import com.queue.core.models.Job;
import com.queue.core.models.JobStatus;
import com.queue.core.repositories.JobRepository;
import com.queue.worker.handlers.TaskHandlerRegistry;
import com.queue.worker.handlers.TaskHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class WorkerEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkerEngine.class);

    private final JobTransactionService txService; // transaction layer proxy
    private final JobRepository jobRepository;
    private final TaskHandlerRegistry taskHandlerRegistry;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    public WorkerEngine(JobTransactionService txService,
                        JobRepository jobRepository,
                        TaskHandlerRegistry taskHandlerRegistry) {
        this.txService = txService;
        this.jobRepository = jobRepository;
        this.taskHandlerRegistry = taskHandlerRegistry;
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

        List<String> supportedTasks = taskHandlerRegistry.getSupportedTaskTypes();

        // Safety check: If a worker has NO handlers, it shouldn't poll the DB
        if (supportedTasks.isEmpty()) {
            log.warn("No TaskHandlers registered! Worker is idling.");
            return;
        }

        while (continuousCheck) {
            // Invoking across the proxy bean interface—REQUIRES_NEW transaction
            Optional<UUID> claimedJobId = txService.claimNextJobAtomically(workerId, supportedTasks);

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

        // flag to control sidecar heartbeat thread
        AtomicBoolean isTaskRunning = new AtomicBoolean(true);

        try {
            log.info("Starting task execution context for Job {}", jobId);

            // 1. Fetch the full Job from the database to read its taskType and payload
            Job job = jobRepository.findById(jobId)
                            .orElseThrow(() -> new IllegalStateException("Job disappeared from database: " + jobId));

            // 2. Look up the specific code strategy for this task type
            TaskHandler taskHandler = taskHandlerRegistry.getHandler(job.getTaskType());

            // launch the background heartbeat monitor as a Virtual Thread
            Thread.ofVirtual().start(() -> {
                log.info("Started heartbeat monitor for Job {}", jobId);
                while (isTaskRunning.get()) {
                    try {
                        Thread.sleep(2000); // Pulse every 2 seconds
                        if (isTaskRunning.get()) { // Double-check we didn't finish while sleeping
                            txService.sendHeartbeat(jobId);
                            log.debug("Pulsed heartbeat for Job {}", jobId);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                log.info("Stopped heartbeat monitor for Job {}", jobId);
            });

            // 3. Execute the isolated business logic on main thread
            taskHandler.execute(job);

            // 4. Mark as completed if no exceptions were thrown
            txService.updateJobStatus(jobId, JobStatus.COMPLETED, null);
            log.info("Finished execution successfully for job {}", jobId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            txService.handleJobFailure(jobId, "Task execution interrupted via runtime signal.");
        } catch (Exception e) {
            txService.handleJobFailure(jobId, e.getMessage());
        } finally {
            // 5. GUARANTEE the heartbeat monitor stops, even if the handler throws an exception!
            isTaskRunning.set(false);
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