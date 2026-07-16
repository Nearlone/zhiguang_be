package com.tongji.knowpost.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.counter.service.UserCounterService;
import com.tongji.knowpost.api.dto.KnowPostPublishResponse;
import com.tongji.knowpost.id.SnowflakeIdGenerator;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.service.impl.KnowPostServiceImpl;
import com.tongji.relation.outbox.OutboxMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowPostServiceImplPublishTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void publishesImmediatelyAndSchedulesRagThroughTransactionalOutbox() throws Exception {
        AtomicReference<String> outboxPayload = new AtomicReference<>();
        KnowPostServiceImpl service = service(outboxPayload, false);

        KnowPostPublishResponse response = service.publish(7L, 123L);

        assertThat(response).isEqualTo(new KnowPostPublishResponse(
                "123", "PUBLISHED", "PENDING", "知文已发布，AI问答正在排队准备"));
        JsonNode payload = objectMapper.readTree(outboxPayload.get());
        assertThat(payload.get("event").asText()).isEqualTo("KNOWPOST_PUBLISHED");
        assertThat(payload.get("id").asLong()).isEqualTo(123L);
    }

    @Test
    void propagatesOutboxFailureSoTransactionCanRollback() {
        KnowPostServiceImpl service = service(new AtomicReference<>(), OutboxBehavior.THROW);

        assertThatThrownBy(() -> service.publish(7L, 123L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox unavailable");
    }

    @Test
    void rejectsZeroRowOutboxInsertSoTransactionCanRollback() {
        KnowPostServiceImpl service = service(new AtomicReference<>(), OutboxBehavior.ZERO_ROWS);

        assertThatThrownBy(() -> service.publish(7L, 123L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insert required outbox event failed");
    }

    private KnowPostServiceImpl service(AtomicReference<String> outboxPayload, boolean failOutbox) {
        return service(outboxPayload, failOutbox ? OutboxBehavior.THROW : OutboxBehavior.SUCCESS);
    }

    private KnowPostServiceImpl service(AtomicReference<String> outboxPayload, OutboxBehavior behavior) {
        KnowPostMapper mapper = (KnowPostMapper) Proxy.newProxyInstance(
                KnowPostMapper.class.getClassLoader(),
                new Class<?>[]{KnowPostMapper.class},
                (proxy, method, args) -> {
                    if ("publish".equals(method.getName())) {
                        return 1;
                    }
                    throw new AssertionError("Unexpected KnowPostMapper call: " + method.getName());
                });
        OutboxMapper outboxMapper = (OutboxMapper) Proxy.newProxyInstance(
                OutboxMapper.class.getClassLoader(),
                new Class<?>[]{OutboxMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        if (behavior == OutboxBehavior.THROW) {
                            throw new IllegalStateException("outbox unavailable");
                        }
                        outboxPayload.set(String.valueOf(args[4]));
                        return behavior == OutboxBehavior.ZERO_ROWS ? 0 : 1;
                    }
                    throw new AssertionError("Unexpected OutboxMapper call: " + method.getName());
                });
        UserCounterService userCounterService = (UserCounterService) Proxy.newProxyInstance(
                UserCounterService.class.getClassLoader(),
                new Class<?>[]{UserCounterService.class},
                (proxy, method, args) -> null);

        // publish 只依赖这些对象；其余传 null 可以防止测试误触发布之外的业务链路。
        return new KnowPostServiceImpl(
                mapper, new SnowflakeIdGenerator(), objectMapper, null, null,
                userCounterService, null, null, null, outboxMapper);
    }

    private enum OutboxBehavior {
        SUCCESS,
        ZERO_ROWS,
        THROW
    }
}
