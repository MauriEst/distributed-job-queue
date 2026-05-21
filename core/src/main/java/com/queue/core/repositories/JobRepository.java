package com.queue.core.repositories;

import com.queue.core.models.Job;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    /**
     * Finds the oldest PENDING job, locks the row so no other worker can see it,
     * and skips any rows that are already locked by other threads.
     */
    @Query(value = """
        SELECT * FROM jobs
        WHERE status = 'PENDING'
        ORDER BY created_at ASC 
        LIMIT 1
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<Job> findNextAvailableJobForUpdate();
}
