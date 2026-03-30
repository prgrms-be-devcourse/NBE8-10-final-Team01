package com.back.global.websocket;

import java.util.Map;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;

import com.back.global.security.SecurityUser;
import com.back.global.websocket.dto.CodeUpdateMessage;

import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class BattleWebSocketHandler {

    private final SimpMessagingTemplate messagingTemplate;
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

        messagingTemplate.convertAndSend(
                "/topic/room/" + roomId + "/spectate",
                Map.of(
                        "type", "CODE_UPDATE",
                        "userId", securityUser.getId(),
                        "code", message.code()));

        // 재입장 시 코드 복원을 위해 Redis에 임시 저장
        battleCodeStore.save(roomId, securityUser.getId(), message.code());
    }
}
