package com.back.global.websocket;

import java.util.Map;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.back.domain.battle.battleparticipant.repository.BattleParticipantRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class BattleDisconnectHandler {

    private final WebSocketSessionRegistry sessionRegistry;
    private final BattleParticipantRepository battleParticipantRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * WebSocket 연결이 끊길 때 Spring이 자동으로 발생시키는 이벤트 처리.
     * - 브라우저 닫기, 네트워크 끊김, heartbeat 무응답 모두 이 핸들러로 들어옴.
     * - PLAYING 상태인 참여자만 ABANDONED 처리 (이미 EXIT인 사람은 건드리지 않음).
     * - 정산은 타이머 만료 또는 전원 EXIT 시 BattleScheduler/JudgeService가 처리.
     */
    @EventListener
    @Transactional
    public void handleDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        Long memberId = sessionRegistry.getMemberId(sessionId);
        sessionRegistry.remove(sessionId);

        if (memberId == null) {
            return;
        }

        battleParticipantRepository.findPlayingParticipantByMemberId(memberId).stream().findFirst().ifPresent(participant -> {
                    Long roomId = participant.getBattleRoom().getId();

                    participant.abandon();
                    battleParticipantRepository.save(participant);

                    log.info("배틀 이탈 처리 - memberId={}, roomId={}", memberId, roomId);

                    messagingTemplate.convertAndSend(
                            "/topic/room/" + roomId, Map.of("type", "PARTICIPANT_LEFT", "userId", memberId));
                });
    }
}
