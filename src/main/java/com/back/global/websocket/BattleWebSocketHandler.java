package com.back.global.websocket;

import java.util.HashMap;
import java.util.Map;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

import com.back.global.security.SecurityUser;
import com.back.global.websocket.dto.CodeSyncRequest;
import com.back.global.websocket.dto.CodeUpdateMessage;
import com.back.global.websocket.pubsub.WebSocketMessagePublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Controller
@RequiredArgsConstructor
public class BattleWebSocketHandler {

    private final WebSocketMessagePublisher publisher;
    private final BattleCodeStore battleCodeStore;

    /**
     * 참여자가 코드 변경 시 호출
     * STOMP SEND /app/room/{roomId}/code
     * Body: { "code": "..." }
     *
     * 관전자 채널로만 브로드캐스트 (참여자끼리는 서로 코드 못 봄)
     */
    @MessageMapping("/room/{roomId}/code")
    public void handleCodeUpdate(
            @DestinationVariable Long roomId,
            CodeUpdateMessage message,
            @AuthenticationPrincipal SecurityUser securityUser) {

        if (securityUser == null) {
            log.warn("handleCodeUpdate - securityUser is null roomId={}", roomId);
            return;
        }
        if (securityUser.getId() == null) {
            log.warn("handleCodeUpdate - securityUser.getId() is null roomId={}", roomId);
            return;
        }
        if (message == null) {
            log.warn("handleCodeUpdate - message is null roomId={}", roomId);
            return;
        }
        if (message.code() == null) {
            log.warn("handleCodeUpdate - message.code() is null roomId={} memberId={}", roomId, securityUser.getId());
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "CODE_UPDATE");
        payload.put("userId", securityUser.getId());
        payload.put("code", message.code());
        publisher.publish("/topic/room/" + roomId + "/spectate", payload);

        // 재입장 및 관전자 동기화를 위해 Redis에 임시 저장
        battleCodeStore.save(roomId, securityUser.getId(), message.code());
    }

    /**
     * 관전자가 최신 코드 전체를 요청할 때 호출 (새로고침 등 재동기화)
     * STOMP SEND /app/room/{roomId}/code/sync
     * Body: { "targetUserId": 42 }
     *
     * Redis에 저장된 플레이어의 현재 코드를 CODE_SYNC로 관전자 채널에 전달
     */
    @MessageMapping("/room/{roomId}/code/sync")
    public void handleSyncRequest(@DestinationVariable Long roomId, CodeSyncRequest request) {

        if (request == null) {
            log.warn("handleSyncRequest - request is null roomId={}", roomId);
            return;
        }
        if (request.targetUserId() == null) {
            log.warn("handleSyncRequest - targetUserId is null roomId={}", roomId);
            return;
        }

        String code = battleCodeStore.get(roomId, request.targetUserId());

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "CODE_SYNC");
        payload.put("userId", request.targetUserId());
        payload.put("code", code);
        publisher.publish("/topic/room/" + roomId + "/spectate", payload);
    }
}
