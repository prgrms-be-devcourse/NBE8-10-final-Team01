package com.back.global.websocket;

import java.security.Principal;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.back.domain.battle.battleparticipant.entity.BattleParticipantStatus;
import com.back.domain.battle.battleparticipant.repository.BattleParticipantRepository;
import com.back.domain.battle.battleroom.entity.BattleRoomStatus;
import com.back.global.security.SecurityUser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class BattleDisconnectHandler {

    private final BattleParticipantRepository battleParticipantRepository;
    private final BattleReconnectStore reconnectStore;

    /**
     * WebSocket 연결이 끊길 때 Spring이 자동으로 발생시키는 이벤트 처리.
     * - 브라우저 닫기, 네트워크 끊김, heartbeat 무응답 모두 이 핸들러로 들어옴.
     * - PLAYING 상태인 참여자만 ABANDONED 처리 (이미 EXIT인 사람은 건드리지 않음).
     * - 정산은 타이머 만료 또는 전원 EXIT 시 BattleScheduler/JudgeService가 처리.
     *
     * <p>memberId 조회 방식 변경:
     * 기존: WebSocketSessionRegistry(sessionId → memberId 맵)에서 조회
     * 변경: Spring Security가 HTTP 핸드셰이크 시점에 Principal을 WebSocket 세션에 전파하므로
     *       SessionDisconnectEvent.getUser()로 직접 SecurityUser를 꺼내 memberId를 조회.
     *       별도 레지스트리 없이 인증 정보를 신뢰할 수 있는 단일 출처(Principal)에서 가져옴.
     */
    @EventListener
    @Transactional
    public void handleDisconnect(SessionDisconnectEvent event) {
        // Spring Security가 전파한 Principal에서 SecurityUser를 꺼내 memberId 조회
        Principal principal = event.getUser();
        if (!(principal instanceof UsernamePasswordAuthenticationToken auth)
                || !(auth.getPrincipal() instanceof SecurityUser user)) {
            // 미인증 세션의 연결 종료 (정상적으로는 SecurityConfig의 authenticated()가 미인증 핸드셰이크를 차단)
            log.warn("WebSocket 연결 종료 - 인증 정보 없음, sessionId={}", event.getSessionId());
            return;
        }

        Long memberId = user.getId();

        battleParticipantRepository
                .findPlayingParticipantByMemberId(memberId, BattleParticipantStatus.PLAYING, BattleRoomStatus.PLAYING)
                .ifPresent(participant -> {
                    Long roomId = participant.getBattleRoom().getId();

                    participant.abandon();
                    battleParticipantRepository.save(participant);

                    log.info("배틀 이탈 처리 - memberId={}, roomId={}, grace period 시작", memberId, roomId);

                    // PARTICIPANT_LEFT를 즉시 보내지 않고 15초 유예 기간 부여.
                    // 새로고침 등 의도치 않은 끊김 시 재연결 기회를 준다.
                    // 유예 기간 만료 후 미복귀 시 GracePeriodConsumer가 브로드캐스트.
                    reconnectStore.startGracePeriod(memberId);
                });
    }
}
