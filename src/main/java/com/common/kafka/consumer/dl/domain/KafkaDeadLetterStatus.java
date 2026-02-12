package com.common.kafka.consumer.dl.domain;

public enum KafkaDeadLetterStatus {
    FAILED,
    NORMAL,
    PENDING,
    RETRYING,
}
