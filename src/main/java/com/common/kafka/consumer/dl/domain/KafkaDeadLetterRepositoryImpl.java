package com.common.kafka.consumer.dl.domain;

import com.common.kafka.consumer.dl.resource.KafkaDeadLetterResource;
import com.common.kafka.consumer.dl.resource.MessageQuery;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.util.StringUtils;

import java.util.List;

@RequiredArgsConstructor
public class KafkaDeadLetterRepositoryImpl implements KafkaDeadLetterRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<KafkaDeadLetterResource> findMessages(MessageQuery query, Pageable pageable) {
        List<KafkaDeadLetterResource> content = queryFactory
                .select(Projections.fields(KafkaDeadLetterResource.class,
                        kafkaDeadLetter.id.as("kafkaDeadLetterId"),
                        kafkaDeadLetter.topic,
                        kafkaDeadLetter.messageKey,
                        kafkaDeadLetter.payload,
                        kafkaDeadLetter.exceptionMessage,
                        kafkaDeadLetter.status,
                        kafkaDeadLetter.regDttm,
                        kafkaDeadLetter.regUser,
                        kafkaDeadLetter.updateDttm,
                        kafkaDeadLetter.updateUser))
                .from(kafkaDeadLetter)
                .where(
                        eqStartId(query.getStartId()),
                        eqEndId(query.getEndId()),
                        eqTopic(query.getTopic()),
                        eqStatus(query.getStatus()),
                        goeFromDate(query.getFromDate()),
                        ltToDate(query.getToDate()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .orderBy(kafkaDeadLetter.id.desc())
                .fetch();

        Long total = queryFactory
                .select(kafkaDeadLetter.count())
                .from(kafkaDeadLetter)
                .where(
                        eqStartId(query.getStartId()),
                        eqEndId(query.getEndId()),
                        eqTopic(query.getTopic()),
                        eqStatus(query.getStatus()),
                        goeFromDate(query.getFromDate()),
                        ltToDate(query.getToDate()))
                .fetchOne();

        return new PageImpl<>(content, pageable, total != null ? total : 0);
    }

    private BooleanExpression eqStartId(Long startId) {
        return startId != null ? kafkaDeadLetter.id.goe(startId) : null;
    }

    private BooleanExpression eqEndId(Long endId) {
        return endId != null ? kafkaDeadLetter.id.loe(endId) : null;
    }

    private BooleanExpression eqTopic(String topic) {
        return StringUtils.hasText(topic) ? kafkaDeadLetter.topic.eq(topic) : null;
    }

    private BooleanExpression eqStatus(KafkaDeadLetterStatus status) {
        return status != null ? kafkaDeadLetter.status.eq(status) : null;
    }

    private BooleanExpression goeFromDate(java.time.LocalDate fromDate) {
        return fromDate != null ? kafkaDeadLetter.regDttm.goe(fromDate.atStartOfDay()) : null;
    }

    private BooleanExpression ltToDate(java.time.LocalDate toDate) {
        return toDate != null ? kafkaDeadLetter.regDttm.lt(toDate.plusDays(1).atStartOfDay()) : null;
    }
}
