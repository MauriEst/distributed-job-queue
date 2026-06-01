package com.queue.api;

import com.queue.core.models.Job;
import com.queue.core.models.JobStatus;
import com.queue.core.repositories.JobRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/jobs")
public class JobController {
    private final JobRepository jobRepository;
    private final StringRedisTemplate redisTemplate;

    public JobController(JobRepository jobRepository, StringRedisTemplate redisTemplate) {
        this.jobRepository = jobRepository;
        this.redisTemplate = redisTemplate;
    }

    @PostMapping
    public ResponseEntity<Job> createJob(@RequestBody Map<String, Object> request) {
        Job job = new Job();
        job.setTaskType((String) request.get("taskType"));
        job.setPayload((String) request.get("payload"));
        job.setMaxRetries((Integer) request.get("maxRetries"));
        job.setStatus(JobStatus.PENDING);

        // Allow the client to specify a future execution time (ISO-8601 format string)
        if (request.containsKey("executeAt")) {
            job.setExecuteAt(OffsetDateTime.parse((String) request.get("executeAt")));
        } else {
            job.setExecuteAt(OffsetDateTime.now());
        }

        Job savedJob = jobRepository.save(job);

        // EVENT TRIGGER: We still notify Redis. If the job is scheduled for the future,
        // the worker will wake up, check the DB, see nothing is ripe, and go back to sleep.
        redisTemplate.convertAndSend("job_channel", savedJob.getId().toString());

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(savedJob);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Job> getJob(@PathVariable UUID id) {
        return jobRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
