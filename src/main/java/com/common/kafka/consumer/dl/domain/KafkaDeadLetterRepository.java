package com.common.kafka.consumer.dl.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KafkaDeadLetterRepository
        extends JpaRepository<KafkaDeadLetter, Long>, KafkaDeadLetterRepositoryCustom {
    List<KafkaDeadLetter> findAllByIdBetweenAndStatus(Long startId, Long endId, KafkaDeadLetterStatus status);
}
