package com.common.kafka.consumer.dl.resource;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ResendMessageCommand {
    private String targetTopic;
    private int partition;
    private long startOffset;
    private long endOffset;
}
