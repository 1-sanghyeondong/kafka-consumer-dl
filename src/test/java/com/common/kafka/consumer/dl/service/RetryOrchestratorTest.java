package com.common.kafka.consumer.dl.service;

import com.common.kafka.constant.ResiliencyHeader;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RetryOrchestratorTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private Counter counter;

    @InjectMocks
    private RetryOrchestrator retryOrchestrator;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(retryOrchestrator, "delayMs", 100);
        ReflectionTestUtils.setField(retryOrchestrator, "maxRetryCount", 3);
        ReflectionTestUtils.setField(retryOrchestrator, "dlqSuffix", "-dlq");

        when(meterRegistry.counter(anyString(), anyString(), anyString())).thenReturn(counter);
        when(meterRegistry.counter(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(counter);
    }

    @Test
    void testProcess_ResendToOriginalTopic() {
        // given
        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader(ResiliencyHeader.ORIGINAL_TOPIC.getKey(), "test-topic".getBytes()));
        headers.add(new RecordHeader(ResiliencyHeader.FORWARDED_AT.getKey(),
                String.valueOf(System.currentTimeMillis()).getBytes()));
        headers.add(new RecordHeader("x-retry-count", "1".getBytes()));

        ConsumerRecord<String, String> record = new ConsumerRecord<>("test-topic-retry-1m", 0, 0, "key", "value");
        record.headers().add(new RecordHeader(ResiliencyHeader.ORIGINAL_TOPIC.getKey(), "test-topic".getBytes()));
        record.headers().add(new RecordHeader(ResiliencyHeader.FORWARDED_AT.getKey(),
                String.valueOf(System.currentTimeMillis()).getBytes()));
        record.headers().add(new RecordHeader("x-retry-count", "1".getBytes()));

        // when
        retryOrchestrator.process(record);

        // then
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());

        ProducerRecord<String, String> sentRecord = captor.getValue();
        assertEquals("test-topic", sentRecord.topic());
        assertEquals("2", new String(sentRecord.headers().lastHeader("x-retry-count").value()));
        verify(counter).increment();
    }

    @Test
    void testProcess_SendToDlq() {
        // given
        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader(ResiliencyHeader.ORIGINAL_TOPIC.getKey(), "test-topic".getBytes()));
        headers.add(new RecordHeader(ResiliencyHeader.FORWARDED_AT.getKey(),
                String.valueOf(System.currentTimeMillis()).getBytes()));
        headers.add(new RecordHeader("x-retry-count", "3".getBytes()));

        ConsumerRecord<String, String> record = new ConsumerRecord<>("test-topic-retry-1m", 0, 0, "key", "value");
        record.headers().add(new RecordHeader(ResiliencyHeader.ORIGINAL_TOPIC.getKey(), "test-topic".getBytes()));
        record.headers().add(new RecordHeader(ResiliencyHeader.FORWARDED_AT.getKey(),
                String.valueOf(System.currentTimeMillis()).getBytes()));
        record.headers().add(new RecordHeader("x-retry-count", "3".getBytes()));

        // when
        retryOrchestrator.process(record);

        // then
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());

        ProducerRecord<String, String> sentRecord = captor.getValue();
        assertEquals("test-topic-dlq", sentRecord.topic());
        verify(counter).increment();
    }

    @Test
    void testProcess_MissingHeader_SendToDlq() {
        // given
        ConsumerRecord<String, String> record = new ConsumerRecord<>("test-topic-retry-1m", 0, 0, "key", "value");

        // when
        retryOrchestrator.process(record);

        // then
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());

        ProducerRecord<String, String> sentRecord = captor.getValue();
        assertEquals("test-topic-retry-1m-dlq", sentRecord.topic());
        verify(counter).increment();
    }
}
