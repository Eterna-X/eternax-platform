package com.eternax.recon.platform.kafka;

import com.eternax.recon.events.Topics;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Declares every topic (and its dead-letter topic) so environments without an operator-managed
 * topology still start correctly. Production disables this and provisions topics via infrastructure
 * code ({@code eternax.kafka.create-topics=false}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "eternax.kafka.create-topics",
        havingValue = "true",
        matchIfMissing = true)
public class PlatformTopics {

    @Bean
    public KafkaAdmin.NewTopics eternaxTopics(
            @Value("${eternax.kafka.partitions:12}") int partitions,
            @Value("${eternax.kafka.replicas:1}") int replicas) {
        List<String> regular =
                List.of(
                        Topics.RAW_RECORDS,
                        Topics.CANONICAL_TRANSACTIONS,
                        Topics.BREAKS,
                        Topics.LATE_MATCHES,
                        Topics.CASE_EVENTS,
                        Topics.ADJUSTMENT_EVENTS,
                        Topics.AUDIT_EVENTS,
                        Topics.CLOSURE_EVENTS);
        var topics = new java.util.ArrayList<org.apache.kafka.clients.admin.NewTopic>();
        for (String name : regular) {
            topics.add(TopicBuilder.name(name).partitions(partitions).replicas(replicas).build());
            topics.add(
                    TopicBuilder.name(name + Topics.DEAD_LETTER_SUFFIX)
                            .partitions(1)
                            .replicas(replicas)
                            .build());
        }
        topics.add(
                TopicBuilder.name(Topics.DEFINITIONS)
                        .partitions(3)
                        .replicas(replicas)
                        .config("cleanup.policy", "compact")
                        .build());
        return new KafkaAdmin.NewTopics(
                topics.toArray(new org.apache.kafka.clients.admin.NewTopic[0]));
    }
}
