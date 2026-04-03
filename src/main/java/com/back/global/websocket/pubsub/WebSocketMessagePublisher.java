package com.back.global.websocket.pubsub;

import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketMessagePublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    static final String CHANNEL = "ws:messages";

    /**
     * publish
     * 모든 서버의 구독자에게 WebSocket 메시지를 전달하기 위해 Redis 채널에 발행.
     * 보낼 대상 STOMP topic과 보낼 payload를 JSON으로 묶어서
     * Redis Pub/Sub 채널 ws:messages에 발행
     *
     * WebSocket으로 전달할 메시지를 직접 보내는 대신, Redis Pub/Sub 채널 ws:messages에 JSON 형태로 발행해서
     * 모든 서버가 그 메시지를 받아 각자의 WebSocket 클라이언트에게 뿌릴 수 있게 하는 발행기 클래스
     *
     * @param stompTopic 최종적으로 convertAndSend할 STOMP 목적지 (예: "/topic/room/1")
     * @param payload    메시지 본문 (Map 등 직렬화 가능한 객체)
     */
    public void publish(String stompTopic, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("topic", stompTopic, "payload", payload));
            redisTemplate.convertAndSend(CHANNEL, json);
        } catch (JsonProcessingException e) {
            log.error("WebSocket 메시지 직렬화 실패 topic={}", stompTopic, e);
        }
    }
}
