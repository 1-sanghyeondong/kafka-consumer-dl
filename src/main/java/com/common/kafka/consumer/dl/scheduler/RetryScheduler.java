package com.common.kafka.consumer.dl.scheduler;

import com.common.kafka.consumer.dl.orchestrator.RetryOrchestrator;
import com.common.kafka.consumer.dl.resource.RetryMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RetryScheduler {
    private final RedisTemplate<String, Object> redisTemplate;
    private final RetryOrchestrator retryOrchestrator;
    private final ObjectMapper objectMapper;

    private static final String LUA_SCRIPT = "local queue = KEYS[1]\n" +
            "local maxScore = ARGV[1]\n" +
            "local limit = ARGV[2]\n" +
            "local items = redis.call('ZRANGEBYSCORE', queue, '-inf', maxScore, 'LIMIT', 0, limit)\n" +
            "if #items > 0 then\n" +
            "    redis.call('ZREM', queue, unpack(items))\n" +
            "end\n" +
            "return items";

    @SuppressWarnings("unchecked")
    @Scheduled(fixedDelay = 1000)
    public void scheduleRetry() {
        try {
            long now = System.currentTimeMillis();

            DefaultRedisScript<List<Object>> redisScript = new DefaultRedisScript<>(LUA_SCRIPT, (Class<List<Object>>) (Class<?>) List.class);
            List<Object> items = redisTemplate.execute(redisScript, redisTemplate.getStringSerializer(), (RedisSerializer<List<Object>>) redisTemplate.getValueSerializer(), Collections.singletonList(RetryOrchestrator.REDIS_QUEUE_KEY), String.valueOf(now), "50");
            if (items.isEmpty()) {
                return;
            }

            for (Object item : items) {
                RetryMessage dto;
                if (item instanceof RetryMessage) {
                    dto = (RetryMessage) item;
                } else {
                    dto = objectMapper.convertValue(item, RetryMessage.class);
                }

                retryOrchestrator.resend(dto);
            }
        } catch (Exception ex) {
            log.error("error polling redis delay queue | message: {}", ex.getMessage(), ex);
        }
    }
}
