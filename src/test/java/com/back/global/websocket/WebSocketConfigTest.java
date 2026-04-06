package com.back.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Collection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.test.util.ReflectionTestUtils;

class WebSocketConfigTest {

    @Test
    @DisplayName("개인 matching 채널 라우팅을 위해 simple broker에 /queue prefix를 포함한다")
    @SuppressWarnings("unchecked")
    void configureMessageBroker_includesQueuePrefixForUserDestination() {
        WebSocketConfig webSocketConfig =
                new WebSocketConfig(mock(WsTokenStore.class), mock(BattleRoomSubscribeInterceptor.class));
        MessageBrokerRegistry registry =
                new MessageBrokerRegistry(new ExecutorSubscribableChannel(), new ExecutorSubscribableChannel());

        webSocketConfig.configureMessageBroker(registry);

        Object simpleBrokerRegistration = ReflectionTestUtils.getField(registry, "simpleBrokerRegistration");
        Collection<String> destinationPrefixes =
                (Collection<String>) ReflectionTestUtils.getField(simpleBrokerRegistration, "destinationPrefixes");

        assertThat(destinationPrefixes).containsExactly("/topic", "/queue");
        assertThat(ReflectionTestUtils.getField(registry, "userDestinationPrefix"))
                .isEqualTo("/user");
    }
}
