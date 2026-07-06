package com.common.kafka.consumer.dl.orchestrator;

import com.common.kafka.consumer.dl.configuration.properties.RetryWorkerProperties;
import com.common.kafka.consumer.dl.domain.KafkaDeadLetter;
import com.common.kafka.consumer.dl.domain.KafkaDeadLetterRepository;
import com.common.kafka.consumer.dl.domain.KafkaDeadLetterStatus;
import com.common.kafka.consumer.dl.resource.RetryMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RetryOrchestrator {

    private final RetryWorkerProperties properties;
    private final RedisTemplate<String, Object> redisTemplate;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public static final String REDIS_QUEUE_KEY = "platform:retry:queue";
    public static final String RETRY_COUNT_HEADER = "x-retry-count";

    public RetryOrchestrator(RetryWorkerProperties properties, RedisTemplate<String, Object> redisTemplate, @Qualifier("commonKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate, ObjectMapper objectMapper) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void process(ConsumerRecord<String, String> record) {
        String originalTopic = getHeader(record, ResiliencyHeader.ORIGINAL_TOPIC.getKey());
        int currentRetryCount = getIntHeader(record, RETRY_COUNT_HEADER);

        if (originalTopic == null) {
            log.warn("missing original topic header | topic: {}, partition: {}, key: {}", record.topic(), record.partition(), record.key());
            return;
        }

        if (currentRetryCount >= properties.getMaxRetryCount()) {
            log.warn("max retry reached ({}) | topic: {}, partition: {}, key: {}", currentRetryCount, record.topic(), record.partition(), record.key());
            return;
        }

        long delay = properties.getDelayMs() * (long) Math.pow(2, currentRetryCount);
        long score = System.currentTimeMillis() + delay;

        Object value = record.value();
        String strValue = record.value();
        if (strValue != null) {
            String trimmed = strValue.trim();

            if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                    (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                try {
                    value = objectMapper.readValue(trimmed, Object.class);
                } catch (Exception e) {
                    // 파싱 실패 시 무시 (원본 String 그대로 유지)
                }
            }
        }

        RetryMessage dto = RetryMessage.builder()
                .key(record.key() != null ? record.key() : "null")
                .value(value)
                .originalTopic(originalTopic)
                .headers(extractHeaders(record))
                .retryCount(currentRetryCount)
                .build();

        redisTemplate.opsForZSet().add(REDIS_QUEUE_KEY, dto, score);
        log.info("enqueued to redis | key: {}, score: {}, delay: {}ms", dto.getKey(), score, delay);
    }

    public void resend(RetryMessage dto) {
        List<Header> headers = dto.getHeaders().entrySet().stream()
                .filter(entry -> !entry.getKey().equals(RETRY_COUNT_HEADER))
                .map(entry -> new RecordHeader(entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8)))
                .collect(Collectors.toList());

        int retryCount = dto.getRetryCount() + 1;
        headers.add(new RecordHeader(RETRY_COUNT_HEADER, String.valueOf(retryCount).getBytes(StandardCharsets.UTF_8)));

        kafkaTemplate.send(new ProducerRecord<>(dto.getOriginalTopic(), null, dto.getKey(), dto.getValue(), headers));
        log.info("resent to original topic | topic: {}, key: {}, retry: {}", dto.getOriginalTopic(), dto.getKey(), retryCount);
    }

    private Map<String, String> extractHeaders(ConsumerRecord<String, String> record) {
        Map<String, String> headersMap = new HashMap<>();
        if (record.headers() != null) {
            for (Header header : record.headers()) {
                headersMap.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
            }
        }
        return headersMap;
    }

    private String getHeader(ConsumerRecord<?, ?> record, String key) {
        if (record.headers() == null)
            return null;
        Header header = record.headers().lastHeader(key);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
    }

    private int getIntHeader(ConsumerRecord<?, ?> record, String key) {
        String val = getHeader(record, key);
        if (val == null)
            return 0;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}