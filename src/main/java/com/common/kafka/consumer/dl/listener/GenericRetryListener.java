package com.common.kafka.consumer.dl.listener;

import com.platform.retry.service.RetryOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GenericRetryListener {
    private final RetryOrchestrator retryOrchestrator;

    @KafkaListener(
            topicPattern = "${retry.worker.topic-pattern}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "retryKafkaListenerContainerFactory",
            concurrency = "3"
    )
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            log.info("received | topic: {}, key: {}", record.topic(), record.key());

            // 비즈니스 로직 수행
            retryOrchestrator.process(record);

            // 정상 처리 시 커밋
            ack.acknowledge();

        } catch (Exception e) {
            log.error("fatal Error processing record | key: {}", record.key(), e);
        }
    }
}