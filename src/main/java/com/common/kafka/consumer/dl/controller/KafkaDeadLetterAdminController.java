package com.common.kafka.consumer.dl.controller;

import com.common.kafka.consumer.dl.resource.KafkaDeadLetterResource;
import com.common.kafka.consumer.dl.resource.MessageQuery;
import com.common.kafka.consumer.dl.resource.ResendMessageCommand;
import com.common.kafka.consumer.dl.service.KafkaDeadLetterAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class KafkaDeadLetterAdminController {
    private final KafkaDeadLetterAdminService kafkaDeadLetterAdminService;

    @Operation(
            summary = "등록된 전체 토픽 목록 조회",
            description = "서버 설정에 정의된 kgp.kafka.topic.* 하위의 모든 토픽명과 실제 치환된 값을 반환"
    )
    @GetMapping("/topics")
    public Map<String, String> getConfiguredTopics() {
        return kafkaTopicProperties.getTopic();
    }

    @Operation(
            summary = "DLQ 메시지 구간 재발행",
            description = "특정 파티션에서 시작 오프셋부터 종료 오프셋 구간의 실패 메시지들을 타겟 토픽으로 재발행"
    )
    @ApiLock
    @PostMapping("/messages/resend")
    public void resendMessages(@RequestBody ResendMessageCommand command) {
        kafkaDeadLetterAdminService.resendMessagesByOffset(command);
    }

    @Operation(
            summary = "재시도 토픽의 파티션 목록 조회",
            description = "토픽에 존재하는 파티션 번호 목록(예: [0, 1, 2])을 반환"
    )
    @GetMapping("/partitions/by-topic")
    public List<Integer> findAvailablePartitions() {
        return kafkaDeadLetterAdminService.getAvailablePartitions();
    }

    @Operation(
            summary = "DLQ 파티션 최신 메시지 조회",
            description = "선택한 DLQ 토픽과 파티션에서 가장 최신의 실패 메시지 목록을 역순 조회 (limit 200개 최대)"
    )
    @GetMapping("/messages/recent")
    public List<KafkaDeadLetterResource> findRecentMessages(@ModelAttribute MessageQuery query) {
        return kafkaDeadLetterAdminService.findRecentMessages(query);
    }

    @Operation(
            summary = "Redis 지연 큐 삭제",
            description = "관리되는 재시도 대기열에서 특정 키의 항목을 강제 삭제 *복구 불가능하므로 주의가 필요"
    )
    @ApiLock
    @DeleteMapping("/retry-queue")
    public void deleteRetryQueue(
            @LockParam @RequestParam(required = false) String key,
            @LockParam @RequestParam(required = false, defaultValue = "false") boolean all) {
        kafkaDeadLetterAdminService.deleteRetryQueue(key, all);
    }
}
