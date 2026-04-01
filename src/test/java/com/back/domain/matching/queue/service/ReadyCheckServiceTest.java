package com.back.domain.matching.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.back.domain.battle.battleroom.dto.CreateRoomRequest;
import com.back.domain.battle.battleroom.dto.CreateRoomResponse;
import com.back.domain.battle.battleroom.service.BattleRoomService;
import com.back.domain.matching.queue.adapter.QueueProblemPicker;
import com.back.domain.matching.queue.dto.MatchStateV2Response;
import com.back.domain.matching.queue.dto.MatchStatus;
import com.back.domain.matching.queue.dto.QueueJoinRequest;
import com.back.domain.matching.queue.dto.QueueStateV2Response;
import com.back.domain.matching.queue.dto.QueueStatusResponse;
import com.back.domain.matching.queue.model.Difficulty;
import com.back.domain.matching.queue.model.QueueKey;
import com.back.domain.matching.queue.model.WaitingUser;
import com.back.domain.matching.queue.store.InMemoryMatchStateStore;
import com.back.domain.matching.queue.store.MatchStateStore;
import com.back.domain.member.member.entity.Member;
import com.back.domain.member.member.repository.MemberRepository;
import com.back.global.exception.ServiceException;

class ReadyCheckServiceTest {

    private final BattleRoomService battleRoomService = mock(BattleRoomService.class);
    private final QueueProblemPicker queueProblemPicker = mock(QueueProblemPicker.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final MatchStateStore matchStateStore = new InMemoryMatchStateStore();

    private final ReadyCheckService readyCheckService =
            new ReadyCheckService(battleRoomService, queueProblemPicker, matchStateStore, memberRepository);

    @BeforeEach
    void setUp() {
        when(queueProblemPicker.pick(any(QueueKey.class), anyList())).thenReturn(1L);
        when(memberRepository.findAllById(anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            return ids.stream()
                    .map(id -> Member.of(id, "user" + id + "@test.com", "user" + id))
                    .toList();
        });
    }

    @Test
    @DisplayName("v2 queue/me는 SEARCHING 동안 waitingCount와 requiredCount를 반환한다")
    void getMyQueueStateV2_returnsSearchingInfo() {
        QueueStatusResponse response = readyCheckService.joinQueueV2(1L, createRequest("Array", Difficulty.EASY));

        QueueStateV2Response queueState = readyCheckService.getMyQueueStateV2(1L);

        assertThat(response.getWaitingCount()).isEqualTo(1);
        assertThat(queueState.inQueue()).isTrue();
        assertThat(queueState.category()).isEqualTo("ARRAY");
        assertThat(queueState.difficulty()).isEqualTo("EASY");
        assertThat(queueState.waitingCount()).isEqualTo(1);
        assertThat(queueState.requiredCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("SEARCHING 중 같은 사용자가 다시 join하면 409 conflict를 반환한다")
    void joinQueueV2_throwsConflict_whenUserAlreadySearching() {
        readyCheckService.joinQueueV2(1L, createRequest("Array", Difficulty.EASY));

        assertThatThrownBy(() -> readyCheckService.joinQueueV2(1L, createRequest("Array", Difficulty.EASY)))
                .isInstanceOfSatisfying(ServiceException.class, ex -> {
                    assertThat(ex.getResultCode()).isEqualTo("409-1");
                    assertThat(ex.getMsg()).isEqualTo("이미 매칭 대기열에 참가 중인 사용자입니다.");
                });
    }

    @Test
    @DisplayName("4명이 모이면 v2 matches/me는 ACCEPT_PENDING을 반환한다")
    void getMyMatchStateV2_returnsAcceptPending_whenFourthUserJoins() {
        readyCheckService.joinQueueV2(1L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(2L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(3L, createRequest("Array", Difficulty.EASY));
        QueueStatusResponse fourthResponse = readyCheckService.joinQueueV2(4L, createRequest("Array", Difficulty.EASY));

        MatchStateV2Response response = readyCheckService.getMyMatchStateV2(1L);

        assertThat(fourthResponse.getWaitingCount()).isEqualTo(0);
        assertThat(readyCheckService.getMyQueueStateV2(1L).inQueue()).isFalse();
        assertThat(response.status()).isEqualTo(MatchStatus.ACCEPT_PENDING);
        assertThat(response.room()).isNull();
        assertThat(response.readyCheck()).isNotNull();
        assertThat(response.readyCheck().acceptedCount()).isEqualTo(0);
        assertThat(response.readyCheck().requiredCount()).isEqualTo(4);
        assertThat(response.readyCheck().acceptedByMe()).isFalse();
        assertThat(response.readyCheck().participants()).hasSize(4);
    }

    @Test
    @DisplayName("ACCEPT_PENDING 상태 사용자가 다시 join하면 409 conflict를 반환한다")
    void joinQueueV2_throwsConflict_whenUserAlreadyInAcceptPending() {
        readyCheckService.joinQueueV2(1L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(2L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(3L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(4L, createRequest("Array", Difficulty.EASY));

        assertThatThrownBy(() -> readyCheckService.joinQueueV2(1L, createRequest("Array", Difficulty.EASY)))
                .isInstanceOfSatisfying(ServiceException.class, ex -> {
                    assertThat(ex.getResultCode()).isEqualTo("409-1");
                    assertThat(ex.getMsg()).isEqualTo("이미 진행 중인 매칭이 있습니다.");
                });
    }

    @Test
    @DisplayName("전원 수락이 완료되면 ROOM_READY와 roomId를 반환한다")
    void acceptMatch_returnsRoomReady_whenAllUsersAccepted() {
        when(battleRoomService.createRoom(any(CreateRoomRequest.class)))
                .thenReturn(new CreateRoomResponse(100L, "WAITING"));

        readyCheckService.joinQueueV2(1L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(2L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(3L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(4L, createRequest("Array", Difficulty.EASY));

        Long matchId = readyCheckService.getMyMatchStateV2(1L).readyCheck().matchId();

        readyCheckService.acceptMatch(1L, matchId);
        readyCheckService.acceptMatch(2L, matchId);
        readyCheckService.acceptMatch(3L, matchId);
        MatchStateV2Response response = readyCheckService.acceptMatch(4L, matchId);

        assertThat(response.status()).isEqualTo(MatchStatus.ROOM_READY);
        assertThat(response.room()).isNotNull();
        assertThat(response.room().roomId()).isEqualTo(100L);
        assertThat(response.readyCheck().acceptedCount()).isEqualTo(4);
        verify(battleRoomService, times(1)).createRoom(any(CreateRoomRequest.class));
    }

    @Test
    @DisplayName("ROOM_READY 상태 사용자가 다시 join하면 409 conflict를 반환한다")
    void joinQueueV2_throwsConflict_whenUserAlreadyInRoomReady() {
        when(battleRoomService.createRoom(any(CreateRoomRequest.class)))
                .thenReturn(new CreateRoomResponse(100L, "WAITING"));

        readyCheckService.joinQueueV2(1L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(2L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(3L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(4L, createRequest("Array", Difficulty.EASY));

        Long matchId = readyCheckService.getMyMatchStateV2(1L).readyCheck().matchId();

        readyCheckService.acceptMatch(1L, matchId);
        readyCheckService.acceptMatch(2L, matchId);
        readyCheckService.acceptMatch(3L, matchId);
        readyCheckService.acceptMatch(4L, matchId);

        assertThatThrownBy(() -> readyCheckService.joinQueueV2(1L, createRequest("Array", Difficulty.EASY)))
                .isInstanceOfSatisfying(ServiceException.class, ex -> {
                    assertThat(ex.getResultCode()).isEqualTo("409-1");
                    assertThat(ex.getMsg()).isEqualTo("이미 진행 중인 매칭이 있습니다.");
                });
    }

    @Test
    @DisplayName("한 명이라도 거절하면 세션 전체가 CANCELLED로 종료된다")
    void declineMatch_returnsCancelled() {
        readyCheckService.joinQueueV2(1L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(2L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(3L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(4L, createRequest("Array", Difficulty.EASY));

        Long matchId = readyCheckService.getMyMatchStateV2(1L).readyCheck().matchId();

        MatchStateV2Response response = readyCheckService.declineMatch(2L, matchId);

        assertThat(response.status()).isEqualTo(MatchStatus.CANCELLED);
        assertThat(response.message()).isEqualTo("다른 참가자가 매칭을 거절했습니다.");
        assertThat(response.readyCheck().participants()).anySatisfy(participant -> {
            if (participant.userId().equals(2L)) {
                assertThat(participant.decision().name()).isEqualTo("DECLINED");
            }
        });
    }

    @Test
    @DisplayName("CANCELLED 상태에서 한 사용자만 다시 join하면 그 사용자만 새 queue에 들어가고 나머지는 terminal 상태를 유지한다")
    void joinQueueV2_allowsRejoinForOnlyCurrentUser_whenSessionCancelled() {
        readyCheckService.joinQueueV2(1L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(2L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(3L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(4L, createRequest("Array", Difficulty.EASY));

        Long matchId = readyCheckService.getMyMatchStateV2(1L).readyCheck().matchId();
        readyCheckService.declineMatch(2L, matchId);

        QueueStatusResponse rejoinResponse = readyCheckService.joinQueueV2(1L, createRequest("Graph", Difficulty.EASY));

        assertThat(rejoinResponse.getWaitingCount()).isEqualTo(1);
        assertThat(readyCheckService.getMyQueueStateV2(1L).inQueue()).isTrue();
        assertThat(readyCheckService.getMyMatchStateV2(1L).status()).isEqualTo(MatchStatus.IDLE);
        assertThat(readyCheckService.getMyMatchStateV2(2L).status()).isEqualTo(MatchStatus.CANCELLED);
        assertThat(readyCheckService.getMyMatchStateV2(3L).status()).isEqualTo(MatchStatus.CANCELLED);
        assertThat(readyCheckService.getMyMatchStateV2(4L).status()).isEqualTo(MatchStatus.CANCELLED);
    }

    @Test
    @DisplayName("deadline이 지나면 ready-check 세션은 EXPIRED로 조회된다")
    void getMyMatchStateV2_returnsExpired_whenDeadlinePassed() {
        QueueKey queueKey = new QueueKey("Array", Difficulty.EASY);
        List<WaitingUser> users = List.of(
                new WaitingUser(1L, queueKey),
                new WaitingUser(2L, queueKey),
                new WaitingUser(3L, queueKey),
                new WaitingUser(4L, queueKey));

        matchStateStore.markAcceptPending(queueKey, users, LocalDateTime.now().minusSeconds(1));

        MatchStateV2Response response = readyCheckService.getMyMatchStateV2(1L);

        assertThat(response.status()).isEqualTo(MatchStatus.EXPIRED);
        assertThat(response.message()).isEqualTo("수락 시간이 만료되었습니다.");
    }

    @Test
    @DisplayName("EXPIRED 상태에서도 현재 사용자만 cleanup 후 다시 join할 수 있다")
    void joinQueueV2_allowsRejoinForOnlyCurrentUser_whenSessionExpired() {
        QueueKey queueKey = new QueueKey("Array", Difficulty.EASY);
        List<WaitingUser> users = List.of(
                new WaitingUser(1L, queueKey),
                new WaitingUser(2L, queueKey),
                new WaitingUser(3L, queueKey),
                new WaitingUser(4L, queueKey));

        matchStateStore.markAcceptPending(queueKey, users, LocalDateTime.now().minusSeconds(1));

        assertThat(readyCheckService.getMyMatchStateV2(1L).status()).isEqualTo(MatchStatus.EXPIRED);

        QueueStatusResponse rejoinResponse = readyCheckService.joinQueueV2(1L, createRequest("Graph", Difficulty.EASY));

        assertThat(rejoinResponse.getWaitingCount()).isEqualTo(1);
        assertThat(readyCheckService.getMyQueueStateV2(1L).inQueue()).isTrue();
        assertThat(readyCheckService.getMyMatchStateV2(1L).status()).isEqualTo(MatchStatus.IDLE);
        assertThat(readyCheckService.getMyMatchStateV2(2L).status()).isEqualTo(MatchStatus.EXPIRED);
        assertThat(readyCheckService.getMyMatchStateV2(3L).status()).isEqualTo(MatchStatus.EXPIRED);
        assertThat(readyCheckService.getMyMatchStateV2(4L).status()).isEqualTo(MatchStatus.EXPIRED);
    }

    @Test
    @DisplayName("방 생성에 실패하면 CANCELLED 상태와 실패 메시지를 반환한다")
    void acceptMatch_returnsCancelled_whenCreateRoomFails() {
        when(battleRoomService.createRoom(any(CreateRoomRequest.class)))
                .thenThrow(new RuntimeException("room create failed"));

        readyCheckService.joinQueueV2(1L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(2L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(3L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(4L, createRequest("Array", Difficulty.EASY));

        Long matchId = readyCheckService.getMyMatchStateV2(1L).readyCheck().matchId();

        readyCheckService.acceptMatch(1L, matchId);
        readyCheckService.acceptMatch(2L, matchId);
        readyCheckService.acceptMatch(3L, matchId);
        MatchStateV2Response response = readyCheckService.acceptMatch(4L, matchId);

        assertThat(response.status()).isEqualTo(MatchStatus.CANCELLED);
        assertThat(response.message()).isEqualTo("방 생성에 실패해 매칭이 취소되었습니다.");
    }

    @Test
    @DisplayName("ROOM_READY 이후 한 명만 입장하면 그 사용자만 IDLE로 빠지고 나머지는 유지된다")
    void clearMatchedRoom_removesOnlyEnteredUser_inV2Flow() {
        when(battleRoomService.createRoom(any(CreateRoomRequest.class)))
                .thenReturn(new CreateRoomResponse(100L, "WAITING"));

        readyCheckService.joinQueueV2(1L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(2L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(3L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(4L, createRequest("Array", Difficulty.EASY));

        Long matchId = readyCheckService.getMyMatchStateV2(1L).readyCheck().matchId();

        readyCheckService.acceptMatch(1L, matchId);
        readyCheckService.acceptMatch(2L, matchId);
        readyCheckService.acceptMatch(3L, matchId);
        readyCheckService.acceptMatch(4L, matchId);

        readyCheckService.clearMatchedRoom(1L, 100L);

        assertThat(readyCheckService.getMyMatchStateV2(1L).status()).isEqualTo(MatchStatus.IDLE);
        assertThat(readyCheckService.getMyMatchStateV2(2L).status()).isEqualTo(MatchStatus.ROOM_READY);
        assertThat(readyCheckService.getMyMatchStateV2(3L).status()).isEqualTo(MatchStatus.ROOM_READY);
        assertThat(readyCheckService.getMyMatchStateV2(4L).status()).isEqualTo(MatchStatus.ROOM_READY);
    }

    @Test
    @DisplayName("stale session은 재join 시 cleanup 후 정상 참가된다")
    void joinQueueV2_cleansUpStaleSession_whenOnlyUserMatchLinkRemains() throws Exception {
        readyCheckService.joinQueueV2(1L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(2L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(3L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(4L, createRequest("Array", Difficulty.EASY));

        Long matchId = readyCheckService.getMyMatchStateV2(1L).readyCheck().matchId();
        matchSessionMap().remove(matchId);

        QueueStatusResponse response = readyCheckService.joinQueueV2(1L, createRequest("Graph", Difficulty.EASY));

        assertThat(response.getWaitingCount()).isEqualTo(1);
        assertThat(userMatchMap().containsKey(1L)).isFalse();
        assertThat(readyCheckService.getMyQueueStateV2(1L).inQueue()).isTrue();
    }

    @Test
    @DisplayName("terminal session의 마지막 참조자까지 재join하면 세션 본문이 제거된다")
    void joinQueueV2_removesTerminalSession_whenLastReferenceIsCleaned() throws Exception {
        readyCheckService.joinQueueV2(1L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(2L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(3L, createRequest("Array", Difficulty.EASY));
        readyCheckService.joinQueueV2(4L, createRequest("Array", Difficulty.EASY));

        Long matchId = readyCheckService.getMyMatchStateV2(1L).readyCheck().matchId();
        readyCheckService.declineMatch(2L, matchId);

        readyCheckService.joinQueueV2(1L, createRequest("Graph", Difficulty.EASY));
        readyCheckService.joinQueueV2(2L, createRequest("DP", Difficulty.EASY));
        readyCheckService.joinQueueV2(3L, createRequest("Greedy", Difficulty.EASY));
        readyCheckService.joinQueueV2(4L, createRequest("Tree", Difficulty.EASY));

        assertThat(matchSessionMap().containsKey(matchId)).isFalse();
    }

    private QueueJoinRequest createRequest(String category, Difficulty difficulty) {
        return new QueueJoinRequest(category, difficulty);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Long> userMatchMap() throws Exception {
        Field field = InMemoryMatchStateStore.class.getDeclaredField("userMatchMap");
        field.setAccessible(true);
        return (Map<Long, Long>) field.get(matchStateStore);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, ?> matchSessionMap() throws Exception {
        Field field = InMemoryMatchStateStore.class.getDeclaredField("matchSessionMap");
        field.setAccessible(true);
        return (Map<Long, ?>) field.get(matchStateStore);
    }
}
