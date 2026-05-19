package com.queue.api;

import com.queue.core.models.Job;
import com.queue.core.repositories.JobRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/jobs")
public class JobController {
    private final JobRepository jobRepository;

    public JobController(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @PostMapping
    public ResponseEntity<?> createJob(@RequestBody Job jobRequest) {
        Job savedJob = jobRepository.save(jobRequest);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "id", savedJob.getId(),
                "status", savedJob.getStatus(),
                "created_at", savedJob.getCreatedAt()
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Job> getJob(@PathVariable UUID id) {
        return jobRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
