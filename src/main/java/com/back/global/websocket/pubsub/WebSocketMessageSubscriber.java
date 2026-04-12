package com.back.global.websocket.pubsub;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketMessageSubscriber {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 전달자
     * Redis 채널에서 메시지를 수신해 로컬 STOMP 브로커로 전달.
     * RedisMessageListenerContainer가 "onMessage" 메서드명으로 이 빈을 호출함.
     *
     * @param message Redis에서 받은 JSON 문자열
     */
    public void onMessage(String message) {
        try {
            RedisWebSocketEnvelope envelope = objectMapper.readValue(message, RedisWebSocketEnvelope.class);
            if (envelope.mode() == null) {
                log.warn("Redis WebSocket 메시지 mode 누락 - message={}", message);
                return;
            }

            if (envelope.mode() == WebSocketDispatchMode.TOPIC) {
                if (!hasText(envelope.topic())) {
                    log.warn("Redis WebSocket topic 메시지에 topic 누락 - message={}", message);
                    return;
                }

                // topic 브로드캐스트는 각 서버가 로컬 broker에 그대로 fan-out 한다.
                messagingTemplate.convertAndSend(envelope.topic(), envelope.payload());
                return;
            }

            if (envelope.mode() == WebSocketDispatchMode.USER) {
                if (envelope.userId() == null || !hasText(envelope.destination())) {
                    log.warn("Redis WebSocket user 메시지에 필수 필드 누락 - message={}", message);
                    return;
                }

                // 개인 채널 메시지는 각 서버가 자기 서버에 연결된 해당 사용자 세션으로만 fan-out 한다.
                messagingTemplate.convertAndSendToUser(
                        String.valueOf(envelope.userId()), envelope.destination(), envelope.payload());
                return;
            }

            log.warn("Redis WebSocket 메시지에 알 수 없는 mode - message={}", message);
        } catch (Exception e) {
            log.error("Redis WebSocket 메시지 처리 실패 message={}", message, e);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
