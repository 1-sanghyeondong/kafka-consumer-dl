package com.common.kafka.consumer.dl.resource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KafkaDeadLetterResource {
    private long kafkaDeadLetterId;
    private String topic;

    private String originalTopic;
    private String messageKey;
    private String payload;

    private int retryCount;
    private String errorMessage;
    private LocalDateTime failedAt;
}
