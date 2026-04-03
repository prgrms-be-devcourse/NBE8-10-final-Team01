package com.back.global.websocket.pubsub;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
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
            JsonNode node = objectMapper.readTree(message);
            String topic = node.get("topic").asText();
            Object payload = objectMapper.treeToValue(node.get("payload"), Object.class);
            messagingTemplate.convertAndSend(topic, payload);
        } catch (Exception e) {
            log.error("Redis WebSocket 메시지 처리 실패 message={}", message, e);
        }
    }
}
