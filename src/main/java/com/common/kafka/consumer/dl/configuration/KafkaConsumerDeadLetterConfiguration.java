package com.common.kafka.consumer.dl.configuration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

@Configuration
public class KafkaConsumerDeadLetterConfiguration {

    @Value("${spring.kafka.consumer.auto-startup:true}")
    private boolean autoStartup;

    @Bean("retryKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> retryKafkaListenerContainerFactory(
            @Qualifier("commonStringConsumerFactory") ConsumerFactory<String, String> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setAutoStartup(autoStartup);

        return factory;
    }
}