package com.common.kafka.consumer.dl.orchestrator;

import com.common.kafka.listener.aspect.annotation.CommonKafkaListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GenericRetryListener {
    private static final String RETRY_TOPIC = "common-retry-topic";

    private final RetryOrchestrator retryOrchestrator;

    @CommonKafkaListener(
            topics = RETRY_TOPIC,
            groupId = "${spring.application.name}",
            containerFactory = "retryKafkaListenerContainerFactory",
            concurrency = "${retry.worker.concurrency:3}",
            enableResiliency = false
    )
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            log.info("received | topic: {}, key: {}", record.topic(), record.key());
            retryOrchestrator.process(record);

            ack.acknowledge();
        } catch (Exception e) {
            log.error("fatal error processing record | topic: {}, key: {}, payload: {}", record.topic(), record.key(), record.value(), e);
        }
    }
}
