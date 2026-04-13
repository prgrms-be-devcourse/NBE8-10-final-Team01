package com.back.global.websocket.pubsub;

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
     * 모든 서버가 같은 topic 브로드캐스트를 전달할 수 있도록 Redis 채널에 envelope 형태로 발행한다.
     *
     * @param stompTopic 최종적으로 convertAndSend 할 STOMP 목적지
     * @param payload    메시지 본문
     */
    public void publish(String stompTopic, Object payload) {
        publishEnvelope(RedisWebSocketEnvelope.forTopic(stompTopic, payload));
    }

    /**
     * 특정 사용자 개인 채널로 보낼 메시지를 Redis Pub/Sub으로 중계한다.
     * 각 서버는 이 envelope를 받아 자기 서버에 연결된 user session에만 fan-out 한다.
     *
     * @param userId      수신 대상 사용자 ID
     * @param destination convertAndSendToUser에 넘길 개인 destination
     * @param payload     메시지 본문
     */
    public void publishToUser(Long userId, String destination, Object payload) {
        publishEnvelope(RedisWebSocketEnvelope.forUser(userId, destination, payload));
    }

    private void publishEnvelope(RedisWebSocketEnvelope envelope) {
        try {
            String json = objectMapper.writeValueAsString(envelope);
            redisTemplate.convertAndSend(CHANNEL, json);
        } catch (JsonProcessingException e) {
            log.error(
                    "WebSocket 메시지 직렬화 실패 mode={}, topic={}, userId={}, destination={}",
                    envelope.mode(),
                    envelope.topic(),
                    envelope.userId(),
                    envelope.destination(),
                    e);
        }
    }
}
