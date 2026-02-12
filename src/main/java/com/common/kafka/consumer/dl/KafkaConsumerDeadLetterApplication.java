package com.common.kafka.consumer.dl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.common.kafka.listener.aspect.annotation.EnableCommonkafkaListener;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@EnableCommonkafkaListener
@SpringBootApplication
public class KafkaConsumerDeadLetterApplication {
    public static void main(String[] args) {
        SpringApplication.run(KafkaConsumerDeadLetterApplication.class, args);
    }
}