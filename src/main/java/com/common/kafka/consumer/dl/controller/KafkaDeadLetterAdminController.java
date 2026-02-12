package com.common.kafka.consumer.dl.controller;

import com.common.kafka.consumer.dl.resource.KafkaDeadLetterResource;
import com.common.kafka.consumer.dl.resource.MessageQuery;
import com.common.kafka.consumer.dl.service.KafkaDeadLetterAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class KafkaDeadLetterAdminController {
    private final KafkaDeadLetterAdminService kafkaDeadLetterAdminService;

    @ApiLock
    @PostMapping("/messages/resend")
    public void resendMessages(@RequestParam @LockParam Long startId, @RequestParam @LockParam Long endId) {
        kafkaDeadLetterAdminService.resendMessagesFromStartIdAndEndId(startId, endId);
    }

    @GetMapping("/messages")
    public Page<KafkaDeadLetterResource> findMessages(@ModelAttribute MessageQuery query) {
        return kafkaDeadLetterAdminService.findMessages(query);
    }

    @DeleteMapping("/retry-queue")
    public void deleteRetryQueue(@RequestParam(required = false) String key, @RequestParam(required = false, defaultValue = "false") boolean all) {
        kafkaDeadLetterAdminService.deleteRetryQueue(key, all);
    }
}
