package com.common.kafka.consumer.dl.service;

import com.common.kafka.consumer.dl.domain.KafkaDeadLetter;
import com.common.kafka.consumer.dl.domain.KafkaDeadLetterRepository;
import com.common.kafka.consumer.dl.domain.KafkaDeadLetterStatus;
import com.common.kafka.consumer.dl.resource.KafkaDeadLetterResource;
import com.common.kafka.consumer.dl.resource.MessageQuery;
import com.common.kafka.consumer.dl.resource.RetryMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static com.common.kafka.consumer.dl.orchestrator.RetryOrchestrator.REDIS_QUEUE_KEY;
import static com.common.kafka.consumer.dl.orchestrator.RetryOrchestrator.RETRY_COUNT_HEADER;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaDeadLetterAdminService {

    private final KafkaDeadLetterRepository kafkaDeadLetterRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public void resendMessagesFromStartIdAndEndId(Long startId, Long endId) {
        List<KafkaDeadLetter> failedMessages = kafkaDeadLetterRepository.findAllByIdBetweenAndStatus(startId, endId,
                KafkaDeadLetterStatus.FAILED);

        for (KafkaDeadLetter entity : failedMessages) {
            try {
                ProducerRecord<String, Object> record = getStringObjectProducerRecord(entity);
                kafkaTemplate.send(record).get();

                entity.setStatus(KafkaDeadLetterStatus.RETRYING);
                log.info("resent dlt message | id: {}, topic: {}", entity.getId(), entity.getTopic());
            } catch (Exception e) {
                log.error("failed to resend dlt message | id: {}, error: {}", entity.getId(), e.getMessage());
            }
        }
    }

    public Page<KafkaDeadLetterResource> findMessages(MessageQuery query) {
        int pageSize = Math.min(query.getPageSize(), 30);
        int pageNumber = Math.max(query.getPage() - 1, 0);
        PageRequest pageRequest = PageRequest.of(pageNumber, pageSize, Sort.by(Sort.Direction.DESC, "id"));

        return kafkaDeadLetterRepository.findMessages(query, pageRequest);
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
                    RetryMessage dto;
                    if (item instanceof RetryMessage) {
                        dto = (RetryMessage) item;
                    } else {
                        dto = objectMapper.convertValue(item, RetryMessage.class);
                    }

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

    private ProducerRecord<String, Object> getStringObjectProducerRecord(KafkaDeadLetter entity) {
        // x-retry-count 헤더를 0으로 초기화
        RecordHeader retryHeader = new RecordHeader(RETRY_COUNT_HEADER, "0".getBytes(StandardCharsets.UTF_8));

        // x-original-topic 원본 토픽을 포함
        RecordHeader originalTopicHeader = new RecordHeader(ResiliencyHeader.ORIGINAL_TOPIC.getKey(),
                entity.getTopic().getBytes(StandardCharsets.UTF_8));

        Object payload = entity.getPayload();
        try {
            payload = objectMapper.readValue(entity.getPayload(), Object.class);
        } catch (Exception e) {
            // ignore
        }

        return new ProducerRecord<>(entity.getTopic(), null, entity.getMessageKey(), payload,
                List.of(retryHeader, originalTopicHeader));
    }
}
