package com.queue.worker;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

@Configuration
public class RedisListenerConfig {

    @Bean
    RedisMessageListenerContainer container(RedisConnectionFactory connectionFactory,
                                            MessageListenerAdapter listenerAdapter) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // Subscribes our workers to the exact string topic channel
        container.addMessageListener(listenerAdapter, new PatternTopic("job_channel"));
        return container;
    }

    @Bean
    MessageListenerAdapter listenerAdapter(WorkerEngine workerEngine) {
        // Tells Redis to invoke the "pollForJobs" method on our WorkerEngine whenever a signal lands
        return new MessageListenerAdapter(workerEngine, "pollForJobs");
    }
}