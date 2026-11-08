package com.payguard.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * RabbitMQ topology, declared by Spring's RabbitAdmin on first connection.
 *
 * <pre>
 *  outbox publisher ──► [payguard.events] ──payment.event──► (payguard.payment-events) ──► worker
 *                                                                │ basicReject(requeue=false)
 *                                                                ▼
 *                        [payguard.dlx] ──payment.event──► (payguard.payment-events.dlq)  parking lot, 7-day TTL
 * </pre>
 *
 * Retries do NOT use RabbitMQ delay queues. A retry is a new outbox row with a future
 * next_attempt_at, so the delay is exact per message (with jitter), survives broker restarts,
 * and is visible in SQL. See docs/architecture-decisions.md ADR-010.
 */
@Configuration
public class RabbitTopology {

    public static final String EXCHANGE = "payguard.events";
    public static final String QUEUE = "payguard.payment-events";
    public static final String ROUTING_KEY = "payment.event";
    public static final String DLX = "payguard.dlx";
    public static final String DLQ = "payguard.payment-events.dlq";

    @Bean
    DirectExchange eventsExchange() {
        return new DirectExchange(EXCHANGE, true, false);   // durable: survives broker restart
    }

    @Bean
    Queue paymentEventsQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(ROUTING_KEY)
                .build();
    }

    @Bean
    Binding paymentEventsBinding() {
        return BindingBuilder.bind(paymentEventsQueue()).to(eventsExchange()).with(ROUTING_KEY);
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ)
                .ttl((int) Duration.ofDays(7).toMillis())  // the DB (status DEAD) is the permanent record
                .maxLength(100_000L)
                .build();
    }

    @Bean
    Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(ROUTING_KEY);
    }
}
