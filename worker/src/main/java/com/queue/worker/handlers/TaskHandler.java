package com.queue.worker.handlers;

import com.queue.core.models.Job;

public interface TaskHandler {

    String getTaskType();

    void execute(Job job) throws Exception;

}
