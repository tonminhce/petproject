package com.shop.mediaservice.outbox;

import com.shop.common.core.constants.OutboxStatus;
import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.common.kafka.producer.KafkaMessagePublisher.BatchOutcome;
import com.shop.common.kafka.producer.KafkaMessagePublisher.OutboxMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaOutboxRelayConnectionTest {

    @Mock
    private OutboxEventRepository outboxRepo;

    @Mock
    private KafkaMessagePublisher kafkaPublisher;

    private TrackingDataSource dataSource;
    private MediaOutboxRelay relay;

    @BeforeEach
    void setUp() {
        dataSource = new TrackingDataSource();
        PlatformTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        relay = new MediaOutboxRelay(outboxRepo, kafkaPublisher, txManager);
        ReflectionTestUtils.setField(relay, "batchSize", 100);
        ReflectionTestUtils.setField(relay, "maxRetries", 10);
        ReflectionTestUtils.setField(relay, "publishTimeoutMs", 5_000L);
        ReflectionTestUtils.setField(relay, "claimLeaseMs", 60_000L);
    }

    @Test
    void theRelayGivesTheConnectionBackToThePoolBeforeItPublishes() {
        UUID firstAggregate = UUID.randomUUID();
        UUID secondAggregate = UUID.randomUUID();
        OutboxEvent first = row(1L, firstAggregate);
        OutboxEvent second = row(2L, secondAggregate);

        AtomicInteger returnedAtFirstClaim = new AtomicInteger(-1);
        AtomicInteger returnedAtSecondClaim = new AtomicInteger(-1);
        AtomicInteger borrowedAtPublish = new AtomicInteger(-1);

        when(outboxRepo.findClaimableAggregates(anyCollection(), anyInt()))
                .thenReturn(List.of(firstAggregate, secondAggregate));
        when(outboxRepo.claimAggregateEvents(eq(firstAggregate), anyCollection(), anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    returnedAtFirstClaim.set(dataSource.returned());
                    return List.of(first);
                });
        when(outboxRepo.claimAggregateEvents(eq(secondAggregate), anyCollection(), anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    returnedAtSecondClaim.set(dataSource.returned());
                    return List.of(second);
                });
        when(kafkaPublisher.publishBatch(anyList(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    borrowedAtPublish.set(dataSource.borrowed());
                    return publishedOutcome(first, second);
                });
        when(outboxRepo.findByIdForUpdate(1L)).thenReturn(Optional.of(first));
        when(outboxRepo.findByIdForUpdate(2L)).thenReturn(Optional.of(second));

        relay.relay();

        assertThat(borrowedAtPublish.get())
                .as("the relay must hold no database connection while it publishes to the broker")
                .isZero();
        assertThat(returnedAtSecondClaim.get())
                .as("the claim of one aggregate must give its connection back to the pool "
                        + "before the next aggregate is claimed")
                .isGreaterThan(returnedAtFirstClaim.get());
    }

    @Test
    void aBrokerFailureGivesTheClaimBackInsteadOfStrandingIt() {
        OutboxEvent row = row(1L);

        when(outboxRepo.findClaimableAggregates(anyCollection(), anyInt()))
                .thenReturn(List.of(row.getAggregateId()));
        when(outboxRepo.claimAggregateEvents(eq(row.getAggregateId()), anyCollection(), anyString(), anyInt()))
                .thenReturn(List.of(row));
        when(kafkaPublisher.publishBatch(anyList(), anyLong(), any()))
                .thenThrow(new IllegalStateException("broker unreachable"));

        assertThatThrownBy(relay::relay).isInstanceOf(IllegalStateException.class);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> released = ArgumentCaptor.forClass(List.class);
        verify(outboxRepo).releaseClaims(released.capture(), eq(OutboxStatus.SENDING),
                eq(OutboxStatus.PENDING), eq(OutboxStatus.FAILED), anyString(), eq(10), anyString(),
                any(Instant.class));
        assertThat(released.getValue()).containsExactly(row.getId());
        assertThat(dataSource.borrowed())
                .as("the relay must not keep a connection after the broker failed")
                .isZero();
    }

    @Test
    void theRelayTickDoesNotRunTheStrandedClaimRecovery() {
        when(outboxRepo.findClaimableAggregates(anyCollection(), anyInt())).thenReturn(List.of());

        relay.relay();

        verify(outboxRepo, never()).reclaimStaleSending(any(), any(), any(), any(), anyInt(), anyString(), any(Instant.class));
    }

    @Test
    void theStrandedClaimRecoveryRunsOnItsOwnConnection() {
        when(outboxRepo.reclaimStaleSending(any(), any(), any(), any(), anyInt(), anyString(), any(Instant.class))).thenReturn(1);

        relay.recoverStrandedClaims();

        verify(outboxRepo).reclaimStaleSending(any(), any(), any(), any(), anyInt(), anyString(), any(Instant.class));
        assertThat(dataSource.borrowed())
                .as("the recovery must give its connection back to the pool")
                .isZero();
    }

    @Test
    void theEventsOfOneAggregateArePublishedInIdOrder() {
        UUID aggregate = UUID.randomUUID();
        OutboxEvent first = row(1L, aggregate);
        OutboxEvent second = row(2L, aggregate);
        List<OutboxMessage> published = new ArrayList<>();

        when(outboxRepo.findClaimableAggregates(anyCollection(), anyInt())).thenReturn(List.of(aggregate));
        when(outboxRepo.claimAggregateEvents(eq(aggregate), anyCollection(), anyString(), anyInt()))
                .thenReturn(List.of(second, first));
        when(kafkaPublisher.publishBatch(anyList(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    published.addAll(invocation.getArgument(0));
                    return publishedOutcome(first, second);
                });
        when(outboxRepo.findByIdForUpdate(1L)).thenReturn(Optional.of(first));
        when(outboxRepo.findByIdForUpdate(2L)).thenReturn(Optional.of(second));

        relay.relay();

        assertThat(published).extracting(OutboxMessage::value).containsExactly("1", "2");
        assertThat(published).extracting(OutboxMessage::key).containsOnly(aggregate.toString());
    }

    private static OutboxEvent row(Long id) {
        return row(id, UUID.randomUUID());
    }

    private static OutboxEvent row(Long id, UUID aggregate) {
        return OutboxEvent.builder()
                .id(id)
                .eventId(UUID.randomUUID().toString())
                .aggregateType("aggregate")
                .aggregateId(aggregate)
                .eventType("aggregate.test.v1")
                .topic("shop.aggregate.test.v1")
                .payload(String.valueOf(id))
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .build();
    }

    private static BatchOutcome publishedOutcome(OutboxEvent... rows) {
        List<BatchOutcome.RecordOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            outcomes.add(new BatchOutcome.RecordOutcome(i, true, null));
        }
        return new BatchOutcome(rows.length, rows.length, true, outcomes);
    }

    private static final class TrackingDataSource implements DataSource {

        private final AtomicInteger borrowed = new AtomicInteger();
        private final AtomicInteger returned = new AtomicInteger();

        int borrowed() {
            return borrowed.get();
        }

        int returned() {
            return returned.get();
        }

        @Override
        public Connection getConnection() {
            borrowed.incrementAndGet();
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    this::handle);
        }

        @Override
        public Connection getConnection(String username, String password) {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }

        private Object handle(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("close".equals(name)) {
                borrowed.decrementAndGet();
                returned.incrementAndGet();
                return null;
            }
            if ("toString".equals(name)) {
                return "pooled-connection";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return proxy == args[0];
            }
            return defaultValue(method.getReturnType());
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return Boolean.FALSE;
            }
            if (type == byte.class) {
                return (byte) 0;
            }
            if (type == short.class) {
                return (short) 0;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == float.class) {
                return 0F;
            }
            if (type == double.class) {
                return 0D;
            }
            if (type == char.class) {
                return (char) 0;
            }
            return null;
        }
    }
}
}