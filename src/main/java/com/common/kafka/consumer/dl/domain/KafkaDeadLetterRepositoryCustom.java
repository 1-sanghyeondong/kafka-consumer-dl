package com.common.kafka.consumer.dl.domain;

import com.common.kafka.consumer.dl.resource.KafkaDeadLetterResource;
import com.common.kafka.consumer.dl.resource.MessageQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface KafkaDeadLetterRepositoryCustom {
    Page<KafkaDeadLetterResource> findMessages(MessageQuery query, Pageable pageable);
}
