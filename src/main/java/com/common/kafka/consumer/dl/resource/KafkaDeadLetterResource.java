package com.common.kafka.consumer.dl.resource;

import com.common.kafka.consumer.dl.domain.KafkaDeadLetterStatus;
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
    private Long kafkaDeadLetterId;
    private String topic;
    private String messageKey;
    private String payload;
    private String exceptionMessage;
    private KafkaDeadLetterStatus status;
    private LocalDateTime regDttm;
    private String regUser;
    private LocalDateTime updateDttm;
    private String updateUser;
}
