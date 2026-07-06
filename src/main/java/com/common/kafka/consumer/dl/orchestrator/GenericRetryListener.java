package com.common.kafka.consumer.dl.orchestrator;

import com.common.kafka.listener.aspect.annotation.CommonKafkaListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.common.kafka.consumer.dl.orchestrator.RetryOrchestrator.RETRY_COUNT_HEADER;

@Slf4j
@Component
@RequiredArgsConstructor
public class GenericRetryListener {
    public static final String RETRY_TOPIC = "common-retry-topic";

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

    private Map<String, String> extractResiliencyHeaders(ConsumerRecord<?, ?> record) {
        Map<String, String> extracted = new LinkedHashMap<>();
        if (record.headers() == null) {
            return extracted;
        }

        for (ResiliencyHeader resHeader : ResiliencyHeader.values()) {
            Header header = record.headers().lastHeader(resHeader.getKey());
            if (header != null) {
                extracted.put(resHeader.getKey(), new String(header.value(), StandardCharsets.UTF_8));
            }
        }

        Header retryCountHeader = record.headers().lastHeader(RETRY_COUNT_HEADER);
        if (retryCountHeader != null) {
            extracted.put(RETRY_COUNT_HEADER, new String(retryCountHeader.value(), StandardCharsets.UTF_8));
        }

        return extracted;
    }
}
