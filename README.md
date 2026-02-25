<img width="960" height="540" alt="kafka dlq" src="https://github.com/user-attachments/assets/147a8648-1632-4ef8-818c-2f59b1f30a96" />

#### Kafka 메시지 소비 실패한 경우 장애를 격리하고 안정적으로 재처리하기 위한 중앙 집중형 **Retry Worker** 서비스

- **자동 설정**: 복잡한 SASL/OAuth 보안 설정이나 Factory 설정 없이 의존성 추가만으로 즉시 사용 가능
- **장애 격리**: 메시지 처리 실패 시 즉시 Retry 토픽으로 격리하여 파티션 Lag 방지 및 서비스 안정성 확보
- **순서 보장**: 실패한 Key에 대한 Local Blocking을 통해 후속 메시지 처리 순서가 뒤바뀌는 것을 방지
- **중앙 집중**: 각 서비스가 개별적으로 재시도 로직을 구현할 필요 없이 실패된 처리는 모두 `common-retry-topic` 토픽으로 전달하여 재처리
- **지연 처리**: 메시지 헤더 분석을 통해 설정된 시간을 정확히 준수하여 재발행함으로써 시스템 부하를 분산하고 복구 시간을 확보
- **실패 관리**: 최대 재시도 횟수 초과 시 자동으로 DLQ(Dead Letter Queue)로 격리하여 데이터 유실을 방지하고 실패 원인 추적 지원
- **특이 사항**: 기존 프로젝트의 Kafka 설정과 충돌하지 않도록 **격리된 빈(Bean)**을 사용하며, 레거시 인증 방식을 자동 지원

---

#### 🚀 퀵 스타트

##### 1. Gradle 의존성 추가
프로젝트의 `build.gradle` 파일에 아래 의존성을 추가하세요.

```gradle
dependencies {
    implementation 'com.common:common-kafka:1.0.0'
}
```

##### 2. 라이브러리 활성화
Application 클래스 또는 설정 클래스에 `@EnableCommonKafkaListener`를 붙입니다.

```java
@EnableCommonKafkaListener
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

##### 3. application.yml 설정

`application.yml` 파일에 설정을 추가합니다.

```yaml
spring:
  kafka:
    bootstrap-servers: 카프카 브로커 주소 (입력하지 않으면 앱 구동 실패)
```

---

#### 🛠 상세 기능

##### 1. @EnableCommonKafkaListener
기존 `@KafkaListener`를 대체하는 어노테이션이며 DTO 객체로 메시지를 바로 수신

```java
@EnableCommonKafkaListener(topics = "order-events", groupId = "order-service", concurrency = "3")
public void onMessage(ConsumerRecord<String, OrderDto> record) {
    // DTO 객체로 바로 수신 (JsonDeserializer 자동 적용)
    log.info("Order ID: {}", record.value().getOrderId());

    // 비즈니스 로직 수행
    // 예외 발생시 retry & dead letter 처리
    processOrder(record.value());
}
```

| 옵션 | 설명 | 기본값 |
|---|---|---|
| `topics` | 구독할 Kafka 토픽 목록 | 필수 |
| `groupId` | 컨슈머 그룹 ID. 설정하지 않으면 yml 기본값 사용 | "" |
| `concurrency` | 병렬 소비 스레드 개수 | "" |
| `containerFactory` | 사용할 컨테이너 팩토리 빈 이름 | `commonContainerFactory` (고정) |
| `enableResiliency` | 장애 격리 활성화 여부. `false` 설정 시 retry/dead letter 처리 없이 즉시 실패 | `true` |

##### 2. Resiliency (장애 격리) 비활성화
특정 리스너에서 retry 또는 dead letter 처리가 필요하지 않은 경우, `enableResiliency = false`로 설정할 수 있습니다.
이 경우 기존 Kafka 재시도 정책 (0초 10회 재시도) 또한 적용되지 않습니다.

**리스너별 설정 예시**:
```java
@EnableCommonKafkaListener(
    topics = "audit-events", 
    groupId = "audit-service",
    enableResiliency = false  // 이 리스너는 fail-fast (즉시 실패)
)
public void onAuditMessage(ConsumerRecord<String, AuditDto> record) {
    // 예외 발생 시 retry 없이 즉시 실패 처리
    processAudit(record.value());
}
```

##### 3. 장애 격리 메커니즘
비즈니스 로직 수행 중 예외(Exception)가 발생하면 다음과 같이 동작합니다.

*   **Fail-Fast**: 예외를 즉시 감지하고 로그를 출력합니다.
*   **Forwarding**: 해당 메시지를 공통 Retry 토픽(`common-retry-topic`)으로 전송합니다.
*   **Header Storage**: 원본 토픽 정보는 `x-original-topic` 헤더에 저장되어 Retry Worker가 올바른 토픽으로 재발행할 수 있습니다.
*   **ACK**: 원본 토픽에 대해 커밋(ACK) 처리하여, 파티션이 막히는(Lag) 현상을 방지합니다.
*   **Header Injection**: 원본 토픽명, 예외 메시지, 발생 시간 등을 Kafka Header에 심어서 보냅니다.

> **참고**: 모든 실패 메시지는 단일 공통 Retry 토픽(`common-retry-topic`)으로 전송되며 별도의 Retry Worker가 헤더의 `x-original-topic` 정보를 읽어 일정 시간 후 원본 토픽으로 재발행합니다.

##### 4. 순서 보장
특정 Key(예: `User:123`)의 메시지가 실패했을 때, 해당 Key의 후속 메시지들이 먼저 처리되어 데이터 정합성이 깨지는 것을 방지합니다.

*   **Local Blocking**: 실패한 Key는 서버 메모리에 잠시 기록됩니다.
*   **Bypass**: 차단된 Key로 들어오는 후속 메시지들은 로직을 타지 않고 즉시 Retry 토픽으로 우회시킵니다.
*   **TTL**: 기본적으로 50초 동안 차단되며, 이후 자동으로 해제됩니다.

#### 5. 격리 및 복구 절차
메시지의 상태에 따라 원본 토픽으로 재발행하거나 DLQ로 격리합니다.

```mermaid
sequenceDiagram
    participant S as 서비스 (Service)
    participant R as Retry Topic
    participant W as Retry Worker
    participant Redis as Redis (ZSet)
    participant DB as DLQ (DB)

    S->>R: 처리 실패 메시지 전송 (x-original-topic, x-retry-count=0)
    R->>W: 메시지 소비
    W->>Redis: ZSet 추가 (score=now+60s)
    
    Note over W: 10초마다 폴링
    W->>Redis: 실행 시간 된 메시지 조회 & 삭제
    
    alt 재시도 횟수 < Max
        W->>S: 원본 토픽으로 재발행 (x-retry-count++)
    else 재시도 횟수 >= Max
        W->>DB: Dead Letter 저장 (영구 보관)
    end
```

##### Producer 기본 설정

| 설정 항목 (Configuration) | 적용 값 (Value)        | 설정 이유 및 효과                                                                                       |
| :--- |:--------------------|:-------------------------------------------------------------------------------------------------|
| **`acks`** | **`all`**           | **데이터 유실 방지 (최우선)**<br>리더뿐만 아니라 모든 ISR(복제본)에 저장이 확인되어야 성공으로 간주합니다.                               |
| **`retries`** | **`10`**            | 일시적인 네트워크 장애나 브로커 리밸런싱 시 즉시 실패하지 않고 충분히 재시도하여 안정성을 확보합니다.                                        |
| **`delivery.timeout.ms`** | **`120,000`**       | 전송 실패 시 무한 대기를 방지하고, 시간 내에 전송되지 않으면 예외를 발생시켜 Fail-Fast 처리를 돕습니다.                                 |
| **`enable.idempotence`** | **`true`**          | **중복 전송 방지 및 순서 보장**<br>네트워크 오류로 인한 메시지 중복을 막고, 파티션 내 정확한 순서를 보장합니다.                             |
| **`max.in.flight.requests.per.connection`** | **`5`**             | **전송 속도 향상**<br>순서 보장(Idempotence)이 켜진 상태에서 최대로 보낼 수 있는 병렬 요청 수로, 대역폭을 효율적으로 사용합니다.              |
| **`batch.size`** | **`50,000`** (50KB) | 메시지를 하나씩 보내지 않고 최대 50KB까지 모아서 한 번에 전송하여 네트워크 오버헤드를 줄입니다.                                         |
| **`linger.ms`** | **`5`** (5ms)       | **Latency와 Throughput의 균형**<br>배치를 채우기 위해 최대 5ms를 기다립니다. 즉시 전송보다 효율적이며 지연 시간은 인간이 인지하기 힘든 수준입니다. |
| **`compression.type`** | **`lz4`**           | CPU 부하가 적고 압축 속도가 매우 빠른 LZ4 알고리즘을 사용하여 네트워크 전송량을 줄입니다.                                           |

##### Consumer 기본 설정

| 설정 항목 (Configuration) | 적용 값 (Value) | 설정 이유 및 효과 |
| :--- | :--- | :--- |
| **`isolation.level`** | **`read_committed`** | **트랜잭션 데이터 보호**<br>트랜잭션이 완료(Commit)된 메시지만 읽습니다. 결제/정산 로직에서 롤백된 데이터를 무시하여 데이터 정합성을 지킵니다. |
| **`auto.offset.reset`** | **`latest`** | 컨슈머 그룹이 처음 생성되었을 때, 과거 데이터가 아닌 **가장 최신 메시지부터** 소비를 시작합니다. |
| **`enable.auto.commit`** | **`false`** | **수동 커밋 (안정성)**<br>Kafka가 주기적으로 자동 커밋하는 것을 막고, 로직 처리가 완료된 시점에 명시적으로 커밋하여 메시지 유실/중복을 제어합니다. |
| **`AckMode`** | **`BATCH`** | **성능 최적화**<br>메시지 1건마다 커밋(RECORD)하지 않고, 폴링한 배치 단위 처리가 끝났을 때 한 번에 커밋하여 브로커 부하를 줄입니다. |
| **`fetch.min.bytes`** | **`기본값`** (1) | 실시간성을 위해 데이터를 모으지 않고 브로커에 데이터가 있으면 즉시 가져옵니다. (Low Latency) |

##### Admin API
운영 중 발생한 Dead Letter 메시지를 관리하기 위한 REST API를 제공합니다.

| Method | URI | 설명 |
|---|---|---|
| `GET` | `/api/messages` | DLQ(Dead Letter) 메시지 목록 조회 (페이징, 필터링 지원) |
| `POST` | `/api/messages/resend` | DLQ에 저장된 메시지를 원본 토픽으로 재발행 (startId ~ endId 범위) |
| `DELETE` | `/api/messages/retry-queue` | Redis Retry 대기열의 메시지 삭제 (전체 또는 특정 Key) |
