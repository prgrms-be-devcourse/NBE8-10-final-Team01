package com.back.global.websocket.pubsub;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

import lombok.RequiredArgsConstructor;

/**
 * subscribe
 * Redis Pub/Sub로 들어온 메시지를 듣고,
 * 그 메시지를 애플리케이션 내부의 subscriber 객체로 넘겨주는 config
 * 1. Redis 채널 구독 준비
 * 2. 메시지가 오면 처리할 리스너 연결
 */
@Configuration
@RequiredArgsConstructor
public class RedisWebSocketConfig {

    private final WebSocketMessageSubscriber subscriber;

    /**
     * Redis listener container는 보통 메시지가 오면 호출할 리스너가 필요함
     * 그런데 subscriber는 그냥 일반 Spring Bean일 수 있음
     * 그래서 MessageListenerAdapter가 중간에서 받아서
     * subscriber.onMessage(...) 메서드를 대신 호출해줌
     *
     * Redis 메시지 도착
     * → MessageListenerAdapter가 받음
     * → subscriber.onMessage(...) 호출
     *
     * onMessage: 메시지가 오면 subscriber 객체의 onMessage 메서드를 호출하라는 뜻
     */
    @Bean
    public MessageListenerAdapter webSocketMessageListenerAdapter() {
        return new MessageListenerAdapter(subscriber, "onMessage");
    }

    /**
     * Redis 구독을 유지하는 역할
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory, MessageListenerAdapter webSocketMessageListenerAdapter) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();

        container.setConnectionFactory(connectionFactory);
        // WebSocketMessagePublisher.CHANNEL 이라는 Redis 채널을 구독하고
        // 그 채널에 메시지가 오면
        // webSocketMessageListenerAdapter에게 전달
        // 특정 Redis 채널
        // → 메시지 수신
        // → adapter 실행
        // → subscriber.onMessage(...) 실행
        container.addMessageListener(
                webSocketMessageListenerAdapter, new ChannelTopic(WebSocketMessagePublisher.CHANNEL));
        return container;
    }
}
