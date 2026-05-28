package com.queue.core.models;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "jobs")
public class Job {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "task_type", nullable = false)
    private String taskType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status = JobStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 3;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "assigned_worker_id")
    private String assignedWorkerID;

    @Column(name = "last_heartbeat_at")
    private OffsetDateTime lastHeartbeatAt;

    @Column(name = "retries_count", nullable = false)
    private int retriesCount = 0;

    @Column(name = "error_message")
    private String errorMessage;

    // Standard Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public int getRetriesCount() { return retriesCount; }
    public void setRetriesCount(int retriesCount) { this.retriesCount = retriesCount; }
    public String getAssignedWorkerID() { return assignedWorkerID; }
    public void setAssignedWorkerID(String assignedWorkerID) { this.assignedWorkerID = assignedWorkerID; }
    public OffsetDateTime getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(OffsetDateTime lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
