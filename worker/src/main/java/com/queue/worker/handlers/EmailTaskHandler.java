package com.queue.worker.handlers;

import com.queue.core.models.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component // Critical: Tells Spring to manage this class and inject it into our Registry
public class EmailTaskHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(EmailTaskHandler.class);

    @Override
    public String getTaskType() {
        return "SEND_EMAIL"; // This must match the string sent in the API payload
    }

    @Override
    public void execute(Job job) throws Exception {
        log.info("Executing SEND_EMAIL logic for Job ID: {}", job.getId());

        // In a real system, you would parse job.getPayload() here using an ObjectMapper
        // and trigger an external API like SendGrid or AWS SES.

        log.info("Payload received: {}", job.getPayload());

//        throw new RuntimeException("Simulated API failure");
        // Simulating some network I/O latency
        Thread.sleep(15000);

        log.info("Successfully dispatched email for Job ID: {}", job.getId());
    }
}