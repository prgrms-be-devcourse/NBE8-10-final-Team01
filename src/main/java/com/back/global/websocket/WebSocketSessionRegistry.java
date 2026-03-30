package com.back.global.websocket;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class WebSocketSessionRegistry {

    private final Map<String, Long> sessionMemberMap = new ConcurrentHashMap<>();

    public void register(String sessionId, Long memberId) {
        sessionMemberMap.put(sessionId, memberId);
    }

    public Long getMemberId(String sessionId) {
        return sessionMemberMap.get(sessionId);
    }

    public void remove(String sessionId) {
        sessionMemberMap.remove(sessionId);
    }
}
