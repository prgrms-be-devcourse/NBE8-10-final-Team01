package com.back.global.websocket.pubsub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

class WebSocketMessageSubscriberTest {

    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebSocketMessageSubscriber subscriber =
            new WebSocketMessageSubscriber(messagingTemplate, objectMapper);

    @Test
    @DisplayName("TOPIC envelope는 로컬 topic broker로 fan-out 한다")
    void onMessage_topicEnvelope_dispatchesToTopic() throws Exception {
        String message = objectMapper.writeValueAsString(RedisWebSocketEnvelope.forTopic(
                "/topic/matching/queue/DP/EASY", Map.of("type", "QUEUE_STATE_CHANGED")));

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);

        subscriber.onMessage(message);

        verify(messagingTemplate).convertAndSend(topicCaptor.capture(), payloadCaptor.capture());
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());

        assertThat(topicCaptor.getValue()).isEqualTo("/topic/matching/queue/DP/EASY");
        assertThat(payloadCaptor.getValue()).isEqualTo(Map.of("type", "QUEUE_STATE_CHANGED"));
    }

    @Test
    @DisplayName("USER envelope는 로컬 user destination으로 fan-out 한다")
    void onMessage_userEnvelope_dispatchesToUser() throws Exception {
        String message = objectMapper.writeValueAsString(
                RedisWebSocketEnvelope.forUser(7L, "/queue/matching", Map.of("type", "READY_CHECK_STARTED")));

        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> destinationCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);

        subscriber.onMessage(message);

        verify(messagingTemplate)
                .convertAndSendToUser(userCaptor.capture(), destinationCaptor.capture(), payloadCaptor.capture());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));

        assertThat(userCaptor.getValue()).isEqualTo("7");
        assertThat(destinationCaptor.getValue()).isEqualTo("/queue/matching");
        assertThat(payloadCaptor.getValue()).isEqualTo(Map.of("type", "READY_CHECK_STARTED"));
    }

    @Test
    @DisplayName("mode가 누락된 메시지는 로그만 남기고 무시한다")
    void onMessage_missingMode_skipsWithoutThrowing() {
        subscriber.onMessage("{\"topic\":\"/topic/test\",\"payload\":{\"type\":\"IGNORED\"}}");

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }
}
