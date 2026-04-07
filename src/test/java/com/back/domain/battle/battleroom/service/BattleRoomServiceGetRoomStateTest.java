package com.back.domain.battle.battleroom.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.back.domain.battle.battleparticipant.repository.BattleParticipantRepository;
import com.back.domain.battle.battleroom.dto.BattleRoomStateResponse;
import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.battle.battleroom.entity.BattleRoomStatus;
import com.back.domain.battle.battleroom.repository.BattleRoomRepository;
import com.back.domain.battle.result.service.BattleResultService;
import com.back.domain.member.member.repository.MemberRepository;
import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.problem.problem.repository.ProblemRepository;
import com.back.global.exception.ServiceException;
import com.back.global.websocket.BattleCodeStore;
import com.back.global.websocket.BattleReconnectStore;
import com.back.global.websocket.BattleTimerStore;
import com.back.global.websocket.pubsub.WebSocketMessagePublisher;

class BattleRoomServiceGetRoomStateTest {

    private final BattleRoomRepository battleRoomRepository = mock(BattleRoomRepository.class);
    private final BattleParticipantRepository battleParticipantRepository = mock(BattleParticipantRepository.class);
    private final BattleCodeStore battleCodeStore = mock(BattleCodeStore.class);

    private final BattleRoomService sut = new BattleRoomService(
            battleRoomRepository,
            battleParticipantRepository,
            mock(ProblemRepository.class),
            mock(MemberRepository.class),
            mock(WebSocketMessagePublisher.class),
            battleCodeStore,
            mock(BattleReconnectStore.class),
            mock(BattleTimerStore.class),
            mock(BattleResultService.class));

    private static final Long ROOM_ID = 1L;
    private static final Long MEMBER_ID = 10L;

    @Test
    @DisplayName("참여자가 getRoomState 호출 시 방 상태와 본인 코드를 반환한다")
    void getRoomState_참여자_정상반환() {
        BattleRoom room = mockPlayingRoom();
        when(battleParticipantRepository.existsByBattleRoomIdAndMemberId(ROOM_ID, MEMBER_ID))
                .thenReturn(true);
        when(battleParticipantRepository.findByBattleRoom(room)).thenReturn(List.of());
        when(battleCodeStore.get(ROOM_ID, MEMBER_ID)).thenReturn("int main() {}");

        BattleRoomStateResponse response = sut.getRoomState(ROOM_ID, MEMBER_ID);

        assertThat(response.roomId()).isEqualTo(ROOM_ID);
        assertThat(response.myCode()).isEqualTo("int main() {}");
    }

    @Test
    @DisplayName("비참여자가 getRoomState 호출 시 403 예외가 발생한다")
    void getRoomState_비참여자_403예외() {
        mockPlayingRoom();
        when(battleParticipantRepository.existsByBattleRoomIdAndMemberId(ROOM_ID, MEMBER_ID))
                .thenReturn(false);

        assertThatThrownBy(() -> sut.getRoomState(ROOM_ID, MEMBER_ID))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("해당 방의 참여자가 아닙니다.");
    }

    @Test
    @DisplayName("비참여자가 getRoomState 호출 시 participants 조회를 하지 않는다")
    void getRoomState_비참여자_participants_조회안함() {
        mockPlayingRoom();
        when(battleParticipantRepository.existsByBattleRoomIdAndMemberId(ROOM_ID, MEMBER_ID))
                .thenReturn(false);

        assertThatThrownBy(() -> sut.getRoomState(ROOM_ID, MEMBER_ID)).isInstanceOf(ServiceException.class);

        verify(battleParticipantRepository, never()).findByBattleRoom(any());
    }

    @Test
    @DisplayName("존재하지 않는 방에 getRoomState 호출 시 예외가 발생한다")
    void getRoomState_존재하지않는방_예외() {
        when(battleRoomRepository.findByIdWithProblem(ROOM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.getRoomState(ROOM_ID, MEMBER_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("존재하지 않는 방입니다.");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private BattleRoom mockPlayingRoom() {
        Problem problem = mock(Problem.class);
        when(problem.getId()).thenReturn(100L);

        BattleRoom room = mock(BattleRoom.class);
        when(room.getId()).thenReturn(ROOM_ID);
        when(room.getProblem()).thenReturn(problem);
        when(room.getStatus()).thenReturn(BattleRoomStatus.PLAYING);
        when(battleRoomRepository.findByIdWithProblem(ROOM_ID)).thenReturn(Optional.of(room));
        return room;
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
