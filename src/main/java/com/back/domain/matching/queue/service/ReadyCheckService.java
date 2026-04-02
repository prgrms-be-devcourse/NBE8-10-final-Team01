package com.back.domain.matching.queue.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.back.domain.battle.battleroom.dto.CreateRoomRequest;
import com.back.domain.battle.battleroom.dto.CreateRoomResponse;
import com.back.domain.battle.battleroom.service.BattleRoomService;
import com.back.domain.matching.queue.adapter.QueueProblemPicker;
import com.back.domain.matching.queue.dto.MatchStateV2Response;
import com.back.domain.matching.queue.dto.MatchStatus;
import com.back.domain.matching.queue.dto.QueueJoinRequest;
import com.back.domain.matching.queue.dto.QueueStateV2Response;
import com.back.domain.matching.queue.dto.QueueStatusResponse;
import com.back.domain.matching.queue.dto.ReadyCheckSnapshot;
import com.back.domain.matching.queue.dto.ReadyParticipantSnapshot;
import com.back.domain.matching.queue.dto.RoomSnapshot;
import com.back.domain.matching.queue.model.MatchSession;
import com.back.domain.matching.queue.model.MatchSessionStatus;
import com.back.domain.matching.queue.model.QueueKey;
import com.back.domain.matching.queue.model.WaitingUser;
import com.back.domain.matching.queue.store.MatchStateStore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * v2 ready-check 흐름 전담 서비스
 *
 * 이 서비스는 "큐에 참가해 4명을 모으는 것" 자체보다
 * "4명이 모인 뒤 수락/거절을 거쳐 ROOM_READY로 가는 상태 전이"를 책임진다.
 *
 * 그래서 큐 진입 자체보다
 * ACCEPT_PENDING, ROOM_READY, EXPIRED, CANCELLED 같은
 * ready-check 전용 상태를 읽고 바꾸는 역할에 집중한다.
 */
public class ReadyCheckService {

    private static final int REQUIRED_MATCH_SIZE = 4;
    // TODO: 피지컬이슈로 60초로 바꿈, 나중에 15L로 원복해야함
    private static final long READY_CHECK_TIMEOUT_SECONDS = 60L;

    private final BattleRoomService battleRoomService;
    private final QueueProblemPicker queueProblemPicker;
    private final MatchStateStore matchStateStore;
    private final MatchingEventPublisher matchingEventPublisher;

    /**
     * v2 큐 참가
     *
     * 4명이 되기 전까지는 기존과 같은 SEARCHING 의미지만,
     * 4명이 되는 순간에는 즉시 room을 만들지 않고 ready-check 세션만 생성한다.
     */
    public QueueStatusResponse joinQueueV2(Long userId, String nickname, QueueJoinRequest request) {
        QueueKey queueKey = new QueueKey(request.getCategory(), request.getDifficulty());
        // 1L -> {array + hard}
        // array + hard = {user1,} 에들어감
        // 반환값은 큐 사이즈
        // 예: user1 이 "m1" 닉네임으로 queue 에 들어오면 이 snapshot 을 함께 넘긴다.
        // 이후 matches/me 는 같은 nickname 을 재사용하고, members 재조회는 하지 않는다.
        int currentSize = matchStateStore.enqueue(userId, nickname, queueKey);

        if (currentSize < REQUIRED_MATCH_SIZE) {
            matchingEventPublisher.publishQueueStateChanged(queueKey, currentSize);
            // 아직 4명이 안 찼다면 기존 SEARCHING 단계로 머문다.
            return new QueueStatusResponse(
                    "매칭 대기열에 참가했습니다.",
                    queueKey.category(),
                    queueKey.difficulty().name(),
                    currentSize);
        }

        // 4명이 찬 시점에만 SEARCHING -> ACCEPT_PENDING 전환을 시도한다.
        MatchSession matchSession = tryCreateReadyCheckSession(queueKey);

        if (matchSession != null) {
            publishReadyCheckStarted(matchSession);

            int remainingCount = matchStateStore.getWaitingCount(queueKey);
            if (remainingCount > 0) {
                // 예: user1~4가 handoff 되고 user5가 queue에 남아 있다면 남은 대기 인원도 즉시 갱신해준다.
                matchingEventPublisher.publishQueueStateChanged(queueKey, remainingCount);
            }
        }

        return new QueueStatusResponse(
                "매칭이 성사되어 수락 대기 상태로 전환되었습니다.",
                queueKey.category(),
                queueKey.difficulty().name(),
                0);
    }

    public QueueStatusResponse cancelQueueV2(Long userId) {
        // cancel은 아직 queue에 남아 있는 SEARCHING 사용자만 대상으로 한다.
        MatchStateStore.CancelResult cancelResult = matchStateStore.cancel(userId);
        QueueKey queueKey = cancelResult.queueKey();
        matchingEventPublisher.publishQueueStateChanged(queueKey, cancelResult.waitingCount());

        return new QueueStatusResponse(
                "매칭 대기열에서 취소했습니다.", queueKey.category(), queueKey.difficulty().name(), cancelResult.waitingCount());
    }

    public QueueStateV2Response getMyQueueStateV2(Long userId) {
        // queue/me는 SEARCHING UI 전용이므로 store snapshot을 그대로 내려준다.
        return matchStateStore.getQueueStateV2(userId);
    }

    /**
     * v2 matches/me 조회
     *
     * store가 들고 있는 세션 원본을 바탕으로
     * 화면에 필요한 DTO 형태로 다시 조립한다.
     */
    public MatchStateV2Response getMyMatchStateV2(Long userId) {
        // matches/me는 ready-check 전용 snapshot이다.
        // queue 상태는 별도 endpoint로 분리하고, 여기서는 매치 세션이 있을 때만 상태를 조립한다.
        MatchSession matchSession = matchStateStore.findMatchSessionByUserId(userId);

        if (matchSession == null) {
            // 활성 ready-check 세션이 없으면 프론트는 IDLE로 해석한다.
            return new MatchStateV2Response(MatchStatus.IDLE, null, null, null);
        }

        return toMatchStateV2Response(userId, matchSession);
    }

    public MatchStateV2Response acceptMatch(Long userId, Long matchId) {
        MatchSession matchSession = matchStateStore.accept(matchId, userId);

        MatchStateStore.RoomCreationAttempt roomCreationAttempt = matchStateStore.tryBeginRoomCreation(matchId);
        matchSession = roomCreationAttempt.matchSession();

        if (roomCreationAttempt.acquired()) {
            // 마지막 accept 경쟁 상황에서도 room 생성은 이 요청만 수행한다.
            // 선점을 못 한 동시 요청은 현재 세션 상태만 그대로 응답으로 돌려준다.
            // 방 생성 시점을 마지막 accept로 미루는 이유는,
            // 수락하지 않은 매치에 불필요한 방이 만들어지지 않도록 하기 위해서다.
            try {
                // 문제 선택과 방 생성은 전원 수락이 끝난 뒤에만 수행한다.
                Long problemId = queueProblemPicker.pick(matchSession.queueKey(), matchSession.participantIds());
                CreateRoomResponse response = battleRoomService.createRoom(
                        new CreateRoomRequest(problemId, matchSession.participantIds(), REQUIRED_MATCH_SIZE));
                matchSession = matchStateStore.markRoomReady(matchId, response.roomId());
            } catch (RuntimeException e) {
                // 이번 단계에서는 room 생성 실패를 별도 재시도하지 않고 매치를 취소한다.
                matchSession = matchStateStore.cancelMatch(matchId);
            }
        }

        return toMatchStateV2Response(userId, matchSession);
    }

    public MatchStateV2Response declineMatch(Long userId, Long matchId) {
        // 한 명이 거절하면 ready-check 세션 전체가 CANCELLED로 종료된다.
        MatchSession matchSession = matchStateStore.decline(matchId, userId);
        return toMatchStateV2Response(userId, matchSession);
    }

    /**
     * battle room join 성공 후 해당 사용자의 매치 세션 연결을 정리한다.
     *
     * v1 매칭 제거 이후에는 별도 v1 서비스가 아니라
     * ready-check 흐름의 진입점인 이 서비스가 room join 후처리도 함께 맡는다.
     */
    public void clearMatchedRoom(Long userId, Long roomId) {
        matchStateStore.clearMatchedRoom(userId, roomId);
    }

    /**
     * 4명 충족 시 ACCEPT_PENDING 세션을 만든다.
     *
     * 이 시점에는 아직 problem pick이나 room 생성이 일어나지 않는다.
     * ready-check가 실패할 수 있기 때문에, 방 생성은 마지막 accept까지 미룬다.
     */
    private MatchSession tryCreateReadyCheckSession(QueueKey queueKey) {
        List<WaitingUser> matchedUsers = matchStateStore.pollMatchCandidates(queueKey, REQUIRED_MATCH_SIZE);

        if (matchedUsers == null) {
            return null;
        }

        LocalDateTime deadline = LocalDateTime.now().plusSeconds(READY_CHECK_TIMEOUT_SECONDS);

        try {
            // 이 시점에 userQueueMap에서 빠지고 userMatchMap / matchSessionMap으로 이동한다.
            return matchStateStore.markAcceptPending(queueKey, matchedUsers, deadline);
        } catch (RuntimeException e) {
            // 세션 생성 도중 실패하면 poll했던 유저들을 다시 queue 앞쪽으로 복구한다.
            matchStateStore.rollbackPolledUsers(queueKey, matchedUsers);
            matchingEventPublisher.publishQueueStateChanged(queueKey, matchStateStore.getWaitingCount(queueKey));
            throw e;
        }
    }

    private void publishReadyCheckStarted(MatchSession matchSession) {
        log.info("READY_CHECK_STARTED handoff 준비 - participants={}", matchSession.participantIds());
        // handoff 는 같은 세션 참여자 4명에게만 개인 채널로 보낸다.
        // 예: [1,2,3,4]가 매칭되면 /user/queue/matching 으로 각각 ACCEPT_PENDING snapshot 을 받는다.
        matchSession
                .participantIds()
                .forEach(participantId -> matchingEventPublisher.publishReadyCheckStarted(
                        participantId, toMatchStateV2Response(participantId, matchSession)));
    }

    /**
     * 내부 MatchSession을 프론트가 바로 쓸 수 있는 v2 응답 DTO로 변환한다.
     *
     * 상태 enum은 내부 세션 상태와 1:1로 같아 보이지만,
     * 실제 화면에서 보여줄 메시지와 room 블록 포함 여부까지 여기서 함께 정한다.
     */
    // 사용 위치 /me, 수락, 거절
    private MatchStateV2Response toMatchStateV2Response(Long userId, MatchSession matchSession) {
        MatchStatus status = toMatchStatus(matchSession.status());

        if (status == MatchStatus.IDLE) {
            return new MatchStateV2Response(MatchStatus.IDLE, null, null, null);
        }

        // readyCheck / room / message를 나눠 담아두면 프론트가 상태별 UI를 단순하게 분기할 수 있다.
        ReadyCheckSnapshot readyCheckSnapshot = buildReadyCheckSnapshot(userId, matchSession);
        RoomSnapshot roomSnapshot = matchSession.roomId() == null ? null : new RoomSnapshot(matchSession.roomId());
        String message = resolveMessage(matchSession);

        return new MatchStateV2Response(status, readyCheckSnapshot, roomSnapshot, message);
    }

    private MatchStatus toMatchStatus(MatchSessionStatus status) {
        return switch (status) {
            // ROOM_READY는 프론트 관점에서 바로 방 입장 가능한 상태다.
            case ROOM_READY -> MatchStatus.ROOM_READY;
            // ROOM_CREATING은 room 생성 중인 내부 상태일 뿐이라 프론트에는 노출하지 않는다.
            case ACCEPT_PENDING, ROOM_CREATING -> MatchStatus.ACCEPT_PENDING;
            case EXPIRED -> MatchStatus.EXPIRED;
            case CANCELLED -> MatchStatus.CANCELLED;
            case CLOSED -> MatchStatus.IDLE;
        };
    }

    /**
     * participant decision은 store가 들고 있고,
     * nickname처럼 화면에 필요한 부가 정보만 서비스에서 합쳐 최종 응답을 만든다.
     */
    private ReadyCheckSnapshot buildReadyCheckSnapshot(Long userId, MatchSession matchSession) {
        // nickname은 store가 아니라 서비스에서 회원 정보를 합쳐 만든다.
        // 세션에 저장한 nickname snapshot 을 그대로 꺼내 쓴다.
        // 예: participantIds = [1,2], participantNicknames = {1:"m1", 2:"m2"} 이면 participants = [(1,"m1"), (2,"m2")] 로
        // 바로 조립된다.
        // 그래서 matches/me 는 더 이상 members where id in (...) 조회를 다시 만들지 않는다.
        List<ReadyParticipantSnapshot> participants = matchSession.participantIds().stream()
                // 참가자 원본 순서를 기준으로 snapshot을 만들면 프론트가 슬롯 UI를 안정적으로 그릴 수 있다.
                .map(participantId -> new ReadyParticipantSnapshot(
                        participantId,
                        matchSession.participantNicknames().getOrDefault(participantId, String.valueOf(participantId)),
                        matchSession.decisionOf(participantId)))
                .toList();

        return new ReadyCheckSnapshot(
                matchSession.matchId(),
                matchSession.acceptedCount(),
                matchSession.participantIds().size(),
                matchSession.isAcceptedBy(userId),
                matchSession.deadline(),
                participants);
    }

    /**
     * 종료 상태에서는 프론트가 즉시 이유를 보여줄 수 있도록
     * 사람이 읽는 메시지도 함께 내려준다.
     */
    private String resolveMessage(MatchSession matchSession) {
        return switch (matchSession.status()) {
            case EXPIRED -> "수락 시간이 만료되었습니다.";
            case CANCELLED -> matchSession.hasDeclinedParticipant() ? "다른 참가자가 매칭을 거절했습니다." : "방 생성에 실패해 매칭이 취소되었습니다.";
            default -> null;
        };
    }
}
