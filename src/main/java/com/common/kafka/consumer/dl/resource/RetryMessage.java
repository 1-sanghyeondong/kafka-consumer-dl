package com.common.kafka.consumer.dl.resource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetryMessage {
    private String key;
    private Object value;
    private String originalTopic;
    private Map<String, String> headers;
    private int retryCount;
}
