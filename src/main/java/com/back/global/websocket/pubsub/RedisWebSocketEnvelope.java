package com.back.global.websocket.pubsub;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Redis Pub/Sub 채널로 실어 보내는 공용 WebSocket envelope.
 * topic 브로드캐스트와 user destination 전달을 같은 포맷으로 표현한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RedisWebSocketEnvelope(
        WebSocketDispatchMode mode, String topic, Long userId, String destination, Object payload) {

    public static RedisWebSocketEnvelope forTopic(String topic, Object payload) {
        return new RedisWebSocketEnvelope(WebSocketDispatchMode.TOPIC, topic, null, null, payload);
    }

    public static RedisWebSocketEnvelope forUser(Long userId, String destination, Object payload) {
        return new RedisWebSocketEnvelope(WebSocketDispatchMode.USER, null, userId, destination, payload);
    }
}
