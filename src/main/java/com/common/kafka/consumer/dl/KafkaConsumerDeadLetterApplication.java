package com.common.kafka.consumer.dl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class KafkaConsumerDeadLetterApplication {
    public static void main(String[] args) {
        // ✅ 클래스 이름 일치시킴
        SpringApplication.run(KafkaConsumerDeadLetterApplication.class, args);
    }
}