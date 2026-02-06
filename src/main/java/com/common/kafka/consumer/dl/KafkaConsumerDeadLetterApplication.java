package com.common.kafka.consumer.dl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class KafkaConsumerDeadLetterApplication {
    public static void main(String[] args) {
        SpringApplication.run(KafkaConsumerDeadLetterApplication.class, args);
    }
}