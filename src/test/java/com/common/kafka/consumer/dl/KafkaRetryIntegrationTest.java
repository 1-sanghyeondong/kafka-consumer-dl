package com.common.kafka.consumer.dl;

import com.common.kafka.constant.ResiliencyHeader;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class KafkaRetryIntegrationTest {

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("retry.worker.delay-ms", () -> 1000);
    }

    @Test
    void testEndToEndRetryFlow() throws Exception {
        String originalTopic = "order-topic";
        String retryTopic = "order-topic-retry-1m";

        // 1. Produce a message to the retry topic
        KafkaProducer<String, String> producer = new KafkaProducer<>(getProducerProps());
        ProducerRecord<String, String> record = new ProducerRecord<>(retryTopic, "test-key", "test-value");
        record.headers().add(new RecordHeader(ResiliencyHeader.ORIGINAL_TOPIC.getKey(), originalTopic.getBytes()));
        record.headers().add(new RecordHeader(ResiliencyHeader.FORWARDED_AT.getKey(),
                String.valueOf(System.currentTimeMillis()).getBytes()));

        producer.send(record).get();

        // 2. Consume from the original topic to verify it was resent
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(getConsumerProps());
        consumer.subscribe(Collections.singletonList(originalTopic));

        boolean received = false;
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 10000) { // Wait up to 10s
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            if (!records.isEmpty()) {
                received = true;
                ConsumerRecord<String, String> receivedRecord = records.iterator().next();
                assertThat(receivedRecord.key()).isEqualTo("test-key");
                assertThat(receivedRecord.value()).isEqualTo("test-value");
                break;
            }
        }

        assertThat(received).isTrue();
    }

    private Properties getProducerProps() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return props;
    }

    private Properties getConsumerProps() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-group");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return props;
    }
}
