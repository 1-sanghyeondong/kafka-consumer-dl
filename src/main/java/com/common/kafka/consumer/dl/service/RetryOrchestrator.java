package com.common.kafka.consumer.dl.service;

import com.common.kafka.constant.ResiliencyHeader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetryOrchestrator {
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${retry.worker.delay-ms}")
    private long delayMs;

    @Value("${retry.worker.max-retry-count}")
    private int maxRetryCount;

    @Value("${retry.worker.dlq-suffix}")
    private String dlqSuffix;

    private static final String RETRY_COUNT_HEADER = "x-retry-count";

    public void process(ConsumerRecord<String, String> record) {
        String originalTopic = getHeader(record, ResiliencyHeader.ORIGINAL_TOPIC.getKey());

        // 헤더 누락 시 방어 로직
        if (originalTopic == null) {
            log.warn("missing original topic header. key: {}", record.key());
            sendToDlq(record, record.topic() + dlqSuffix, "Missing Header: original-topic");
            return;
        }

        long forwardedAt = getLongHeader(record, ResiliencyHeader.FORWARDED_AT.getKey());
        enforceDelay(forwardedAt);

        int currentRetryCount = getIntHeader(record, RETRY_COUNT_HEADER);
        if (currentRetryCount >= maxRetryCount) {
            log.info("[dlq] max retry reached ({}). key: {}", currentRetryCount, record.key());
            sendToDlq(record, originalTopic + dlqSuffix, "Max Retry Exceeded");
        } else {
            resendToOriginalTopic(record, originalTopic, currentRetryCount + 1);
        }
    }

    private void enforceDelay(long forwardedAt) {
        if (forwardedAt <= 0) return;

        long now = System.currentTimeMillis();
        long elapsed = now - forwardedAt;
        long remaining = delayMs - elapsed;

        if (remaining > 0) {
            try {
                log.debug("sleeping for {} ms", remaining);
                Thread.sleep(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted during retry delay", e);
            }
        }
    }

    private void resendToOriginalTopic(ConsumerRecord<String, String> record, String targetTopic, int nextRetryCount) {
        List<Header> headers = copyHeadersExcept(record, RETRY_COUNT_HEADER);
        headers.add(new RecordHeader(RETRY_COUNT_HEADER, String.valueOf(nextRetryCount).getBytes()));

        ProducerRecord<String, String> producerRecord = new ProducerRecord<>(
                targetTopic, null, record.key(), record.value(), headers
        );

        kafkaTemplate.send(producerRecord);
        log.info("[retry] sent to original: {}. count: {}", targetTopic, nextRetryCount);
    }

    private void sendToDlq(ConsumerRecord<String, String> record, String dlqTopic, String reason) {
        List<Header> headers = copyHeadersExcept(record, null);
        headers.add(new RecordHeader("x-dlq-reason", reason.getBytes(StandardCharsets.UTF_8)));

        ProducerRecord<String, String> dlqRecord = new ProducerRecord<>(
                dlqTopic, null, record.key(), record.value(), headers
        );

        kafkaTemplate.send(dlqRecord);
    }

    private List<Header> copyHeadersExcept(ConsumerRecord<?, ?> record, String ignoreKey) {
        List<Header> headers = new ArrayList<>();
        if (record.headers() != null) {
            for (Header h : record.headers()) {
                if (ignoreKey == null || !h.key().equals(ignoreKey)) {
                    headers.add(h);
                }
            }
        }
        return headers;
    }

    private String getHeader(ConsumerRecord<?, ?> record, String key) {
        if (record.headers() == null) return null;
        Header header = record.headers().lastHeader(key);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
    }

    private int getIntHeader(ConsumerRecord<?, ?> record, String key) {
        String val = getHeader(record, key);
        if (val == null) return 0;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            log.warn("invalid integer header for key: {}. value: {}", key, val);
            return 0;
        }
    }

    private long getLongHeader(ConsumerRecord<?, ?> record, String key) {
        String val = getHeader(record, key);
        if (val == null) return 0L;
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            log.warn("invalid long header for key: {}. value: {}", key, val);
            return 0L;
        }
    }
}