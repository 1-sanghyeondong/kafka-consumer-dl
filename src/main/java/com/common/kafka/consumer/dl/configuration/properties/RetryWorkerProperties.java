package com.common.kafka.consumer.dl.configuration.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "retry.worker")
public class RetryWorkerProperties {
    private long delayMs = 10000;
    private int maxRetryCount = 3;
}
