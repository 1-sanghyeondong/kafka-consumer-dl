package com.common.kafka.consumer.dl.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

@Getter
@Entity
@Builder
@DynamicUpdate
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "kafka_dead_letters")
public class KafkaDeadLetter {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column
    private String topic;

    @Column
    private String messageKey;

    @Lob
    @Column
    private String payload;

    @Column
    private String exceptionMessage;

    @Setter
    @Column
    @Enumerated(EnumType.STRING)
    private KafkaDeadLetterStatus status;

    @Column
    private LocalDateTime createdAt;

    @Column
    private String createdBy;

    @Column
    private LocalDateTime updatedAt;

    @Column
    private String updatedBy;
}
