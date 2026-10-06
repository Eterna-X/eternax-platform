package com.eternax.recon.platform;

import com.eternax.recon.platform.audit.AuditPublisher;
import com.eternax.recon.platform.context.RequestContextFilter;
import com.eternax.recon.platform.idempotency.IdempotencyService;
import com.eternax.recon.platform.kafka.EventCodec;
import com.eternax.recon.platform.kafka.PlatformTopics;
import com.eternax.recon.platform.lock.DistributedLock;
import com.eternax.recon.platform.lock.JdbcDistributedLock;
import com.eternax.recon.platform.outbox.OutboxRecorder;
import com.eternax.recon.platform.outbox.OutboxRelay;
import com.eternax.recon.platform.resilience.ResilientCaller;
import com.eternax.recon.platform.web.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** Wires the platform beans into any service that has the starter on its classpath. */
@AutoConfiguration
@org.springframework.context.annotation.PropertySource(
        "classpath:eternax-platform-defaults.properties")
@EnableScheduling
@Import({GlobalExceptionHandler.class, PlatformTopics.class})
public class PlatformAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public FilterRegistrationBean<RequestContextFilter> eternaxRequestContextFilter(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        FilterRegistrationBean<RequestContextFilter> registration =
                new FilterRegistrationBean<>(new RequestContextFilter(resolver));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    public EventCodec eventCodec(ObjectMapper mapper) {
        return new EventCodec(mapper);
    }

    @Bean
    public OutboxRecorder outboxRecorder(JdbcClient jdbc, ObjectMapper mapper) {
        return new OutboxRecorder(jdbc, mapper);
    }

    @Bean
    public AuditPublisher auditPublisher(
            OutboxRecorder outbox,
            ObjectMapper mapper,
            Clock clock,
            @Value("${spring.application.name}") String serviceName) {
        return new AuditPublisher(outbox, mapper, clock, serviceName);
    }

    @Bean
    public DistributedLock distributedLock(JdbcClient jdbc) {
        return new JdbcDistributedLock(jdbc);
    }

    @Bean
    public IdempotencyService idempotencyService(JdbcClient jdbc) {
        return new IdempotencyService(jdbc);
    }

    @Bean
    public ResilientCaller resilientCaller(
            @Value("${eternax.resilience.attempt-timeout:5s}") Duration timeout,
            @Value("${eternax.resilience.max-attempts:3}") int attempts) {
        return new ResilientCaller(timeout, attempts);
    }

    /**
     * Failed records are retried with exponential backoff, then parked on {@code <topic>.dlt} so
     * one poison message never blocks a partition. Validation-type failures are not retried.
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        var recoverer =
                new DeadLetterPublishingRecoverer(
                        template, (record, ex) -> new TopicPartition(record.topic() + ".dlt", 0));
        var backOff = new ExponentialBackOff(200, 2.0);
        backOff.setMaxElapsedTime(15_000);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                com.eternax.recon.exception.SchemaValidationException.class,
                com.eternax.recon.exception.MappingNotFoundException.class,
                com.eternax.recon.exception.TransformationFailedException.class);
        return handler;
    }

    @Bean
    @ConditionalOnProperty(
            name = "eternax.outbox.enabled",
            havingValue = "true",
            matchIfMissing = true)
    public OutboxRelay outboxRelay(
            JdbcClient jdbc,
            KafkaTemplate<String, String> kafka,
            PlatformTransactionManager tm,
            MeterRegistry meters,
            @Value("${eternax.outbox.batch-size:200}") int batchSize) {
        return new OutboxRelay(jdbc, kafka, new TransactionTemplate(tm), meters, batchSize);
    }

    @Bean
    public com.eternax.recon.platform.closure.ClosedPeriodRegistry closedPeriodRegistry(
            JdbcClient jdbc) {
        return new com.eternax.recon.platform.closure.JdbcClosedPeriodRegistry(jdbc);
    }

    /** Opt-in: services that enforce period locks set {@code eternax.closure-lock.enabled=true}. */
    @Bean
    @ConditionalOnProperty(name = "eternax.closure-lock.enabled", havingValue = "true")
    public com.eternax.recon.platform.closure.ClosureLockListener closureLockListener(
            com.eternax.recon.platform.closure.ClosedPeriodRegistry registry, EventCodec codec) {
        return new com.eternax.recon.platform.closure.ClosureLockListener(registry, codec);
    }
}
