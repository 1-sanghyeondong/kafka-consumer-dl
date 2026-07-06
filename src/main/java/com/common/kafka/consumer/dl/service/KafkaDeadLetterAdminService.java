package com.common.kafka.consumer.dl.service;

import com.common.kafka.consumer.dl.resource.KafkaDeadLetterResource;
import com.common.kafka.consumer.dl.resource.MessageQuery;
import com.common.kafka.consumer.dl.resource.ResendMessageCommand;
import com.common.kafka.consumer.dl.resource.RetryMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import static com.common.kafka.consumer.dl.orchestrator.GenericRetryListener.RETRY_TOPIC;
import static com.common.kafka.consumer.dl.orchestrator.RetryOrchestrator.REDIS_QUEUE_KEY;
import static com.common.kafka.consumer.dl.orchestrator.RetryOrchestrator.RETRY_COUNT_HEADER;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaDeadLetterAdminService {

    private static final int RETRY_MAX_COUNT = 3;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ConsumerFactory<String, Object> consumerFactory;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public void resendMessagesByOffset(ResendMessageCommand command) {
        log.info("starting DLQ replay | topic: {}, target_topic_id: {}, partition: {}, offsets: {} to {}", RETRY_TOPIC, command.getTargetTopic(), command.getPartition(), command.getStartOffset(), command.getEndOffset());

        // 충돌 방지를 위한 고유한 admin consumer group id
        String groupId = "admin-dlq-replay-" + UUID.randomUUID();
        try (Consumer<String, Object> consumer = consumerFactory.createConsumer(groupId, "dlq-replay")) {
            TopicPartition topicPartition = new TopicPartition(RETRY_TOPIC, command.getPartition());
            consumer.assign(Collections.singletonList(topicPartition));

            Map<TopicPartition, Long> beginningOffsets = consumer.beginningOffsets(Collections.singletonList(topicPartition));
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(Collections.singletonList(topicPartition));

            long minOffset = beginningOffsets.getOrDefault(topicPartition, 0L);
            long maxOffset = endOffsets.getOrDefault(topicPartition, 0L);

            if (command.getStartOffset() < minOffset || command.getStartOffset() >= maxOffset) {
                throw new IllegalArgumentException(
                        String.format("요청한 오프셋이 범위를 벗어났습니다 | 현재 유효 범위: %d ~ %d", minOffset, maxOffset - 1)
                );
            }

            // 지정한 시작 오프셋으로 커서 이동
            consumer.seek(topicPartition, command.getStartOffset());

            boolean isFinished = false;
            while (!isFinished) {
                ConsumerRecords<String, Object> records = consumer.poll(Duration.ofMillis(1000));

                if (records.isEmpty()) {
                    log.warn("no more records found in DLQ topic");
                    break;
                }

                for (ConsumerRecord<String, Object> record : records) {
                    if (record.offset() > command.getEndOffset()) {
                        isFinished = true;
                        break;
                    }

                    String retryCountStr = extractHeaderValue(record, RETRY_COUNT_HEADER);
                    int currentRetryCount = retryCountStr != null ? Integer.parseInt(retryCountStr) : 0;

                    // 재시도 횟수가 RETRY_MAX_COUNT 이 아니면 재발행하지 않고 건너뜀
                    if (currentRetryCount != RETRY_MAX_COUNT) {
                        log.info("skipped DLT message (retry count mismatch) | offset: {}, current_retry: {}, target_retry: {}", record.offset(), currentRetryCount, RETRY_MAX_COUNT);
                        continue;
                    }

                    try {
                        ProducerRecord<String, Object> producerRecord = createRetryProducerRecord(command.getTargetTopic(), record);
                        kafkaTemplate.send(producerRecord).get();
                        log.info("resent DLT message | offset: {}, key: {}", record.offset(), record.key());
                    } catch (Exception e) {
                        log.error("failed to resend DLT message | offset: {}, error: {}", record.offset(), e.getMessage());
                    }
                }
            }
        }
    }

    public List<Integer> getAvailablePartitions() {
        String groupId = "admin-metadata-viewer-" + UUID.randomUUID();

        try (Consumer<String, Object> consumer = consumerFactory.createConsumer(groupId, "metadata")) {
            List<PartitionInfo> partitionInfos = consumer.partitionsFor(RETRY_TOPIC);

            if (partitionInfos == null || partitionInfos.isEmpty()) {
                return Collections.emptyList(); // 토픽이 없거나 파티션이 없는 경우
            }

            return partitionInfos.stream()
                    .map(PartitionInfo::partition)
                    .sorted()
                    .toList();
        } catch (Exception ex) {
            log.error("failed to fetch partition info for topic: {} | message: {}", RETRY_TOPIC, ex.getMessage(), ex);
            return Collections.emptyList();
        }
    }

    public List<KafkaDeadLetterResource> findRecentMessages(MessageQuery query) {
        int maxLimit = Math.min(query.getLimit(), 200);
        List<KafkaDeadLetterResource> result = new ArrayList<>();
        String groupId = "admin-dlq-viewer-" + UUID.randomUUID();

        try (Consumer<String, Object> consumer = consumerFactory.createConsumer(groupId, "dlq-viewer")) {
            TopicPartition topicPartition = new TopicPartition(RETRY_TOPIC, query.getPartition());
            consumer.assign(Collections.singletonList(topicPartition));

            consumer.seekToEnd(Collections.singletonList(topicPartition));
            long endOffset = consumer.position(topicPartition);
            if (endOffset == 0) return result;

            Map<TopicPartition, Long> beginningOffsets = consumer.beginningOffsets(Collections.singletonList(topicPartition));
            long beginningOffset = beginningOffsets.get(topicPartition);
            long startOffset = Math.max(beginningOffset, endOffset - maxLimit);

            consumer.seek(topicPartition, startOffset);

            int emptyPollCount = 0;
            while (result.size() < (endOffset - startOffset)) {
                ConsumerRecords<String, Object> records = consumer.poll(Duration.ofMillis(1000));

                if (records.isEmpty()) {
                    emptyPollCount++;
                    if (emptyPollCount >= 3) {
                        log.warn("데이터 읽기 타임아웃 도달. 루프 종료");
                        break;
                    }
                    continue;
                }
                emptyPollCount = 0;

                for (ConsumerRecord<String, Object> record : records) {
                    if (record.offset() >= endOffset) break;

                    record.headers().forEach(header -> {
                        log.info("[Header Debug] Key: {}, Value: {}",
                                header.key(), new String(header.value(), StandardCharsets.UTF_8));
                    });

                    String originalTopic = extractHeaderValue(record, ResiliencyHeader.ORIGINAL_TOPIC.getKey());
                    String retryCountStr = extractHeaderValue(record, RETRY_COUNT_HEADER);

                    int currentRetryCount = retryCountStr != null ? Integer.parseInt(retryCountStr) : 0;
                    if (currentRetryCount != RETRY_MAX_COUNT) {
                        continue;
                    }

                    String errorMessage = extractHeaderValue(record, "kafka_dlt-exception-message");
                    KafkaDeadLetterResource resource = KafkaDeadLetterResource.builder()
                            .kafkaDeadLetterId(record.offset())
                            .topic(RETRY_TOPIC)
                            .originalTopic(originalTopic != null ? originalTopic : "UNKNOWN")
                            .messageKey(record.key())
                            .payload(record.value() != null ? record.value().toString() : null)
                            .retryCount(currentRetryCount)
                            .errorMessage(errorMessage)
                            .failedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(record.timestamp()), ZoneId.systemDefault()))
                            .build();

                    result.add(resource);
                }
            }
        }

        Collections.reverse(result);
        return result;
    }

    public void deleteRetryQueue(String key, boolean all) {
        if (all) {
            redisTemplate.delete(REDIS_QUEUE_KEY);
            log.info("deleted all items in redis retry queue");
            return;
        }

        if (key != null) {
            long count = 0;
            long offset = 0;
            long limit = 100;

            while (true) {
                Set<Object> items = redisTemplate.opsForZSet().range(REDIS_QUEUE_KEY, offset, offset + limit - 1);
                if (items == null || items.isEmpty()) {
                    break;
                }

                for (Object item : items) {
                    RetryMessage dto = (item instanceof RetryMessage) ?
                            (RetryMessage) item : objectMapper.convertValue(item, RetryMessage.class);

                    if (key.equals(dto.getKey())) {
                        redisTemplate.opsForZSet().remove(REDIS_QUEUE_KEY, item);
                        count++;
                    }
                }
                offset += limit;
            }

            log.info("deleted {} items in redis retry queue | key: {}", count, key);
        }
    }

    // kafka consumerRecord를 파싱하여 producerRecord 재가공
    private ProducerRecord<String, Object> createRetryProducerRecord(String targetTopic, ConsumerRecord<String, Object> record) {
        List<Header> updatedHeaders = new ArrayList<>();

        // 기존 헤더 복사
        if (record.headers() != null) {
            record.headers().forEach(updatedHeaders::add);
        }

        // x-retry-count 초기화 (기존 것 지우고 0으로 세팅)
        updatedHeaders.removeIf(h -> h.key().equals(RETRY_COUNT_HEADER));
        updatedHeaders.add(new RecordHeader(RETRY_COUNT_HEADER, "0".getBytes(StandardCharsets.UTF_8)));

        // original topic 강제 재세팅
        updatedHeaders.removeIf(h -> h.key().equals(ResiliencyHeader.ORIGINAL_TOPIC.getKey()));
        updatedHeaders.add(new RecordHeader(ResiliencyHeader.ORIGINAL_TOPIC.getKey(), targetTopic.getBytes(StandardCharsets.UTF_8)));

        return new ProducerRecord<>(targetTopic, null, record.key(), record.value(), updatedHeaders);
    }

    private String extractHeaderValue(ConsumerRecord<?, ?> record, String headerKey) {
        if (record.headers() == null) {
            return null;
        }
        Header header = record.headers().lastHeader(headerKey);
        if (header != null && header.value() != null) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }
        return null;
    }
}