package com.common.kafka.consumer.dl.resource;

import com.common.kafka.consumer.dl.domain.KafkaDeadLetterStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageQuery {
    private int page;
    private int pageSize;
    private Long startId;
    private Long endId;
    private String topic;
    private KafkaDeadLetterStatus status;
    private LocalDate fromDate;
    private LocalDate toDate;
}
