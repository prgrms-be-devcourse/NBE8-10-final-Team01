package com.back.global.websocket.pubsub;

/**
 * Redis Pub/Sub으로 전달한 WebSocket 메시지를 각 서버가 어떤 방식으로 fan-out 할지 나타낸다.
 */
public enum WebSocketDispatchMode {
    TOPIC,
    USER
}
