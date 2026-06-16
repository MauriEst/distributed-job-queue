package com.queue.worker.handlers;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class TaskHandlerRegistry {

    private final Map<String, TaskHandler> handlers;


    public TaskHandlerRegistry(List<TaskHandler> availableHandlers) {
        // We convert the List into a Map for O(1) instantaneous lookups by taskType
        this.handlers = availableHandlers.stream()
                .collect(Collectors.toMap(TaskHandler::getTaskType, Function.identity()));
    }

    public TaskHandler getHandler(String taskType) {
        TaskHandler handler = handlers.get(taskType);
        if (handler == null) {
            throw new IllegalArgumentException("No handler registered for task type: " + taskType);
        }
        return handler;
    }

    public List<String> getSupportedTaskTypes() {
        return new ArrayList<>(handlers.keySet());
    }
}
