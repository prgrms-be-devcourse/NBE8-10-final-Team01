package com.back.domain.battle.result.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.back.domain.battle.battleparticipant.entity.BattleParticipant;
import com.back.domain.battle.battleparticipant.entity.BattleParticipantStatus;
import com.back.domain.battle.battleparticipant.repository.BattleParticipantRepository;
import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.battle.battleroom.entity.BattleRoomStatus;
import com.back.domain.battle.battleroom.repository.BattleRoomRepository;
import com.back.domain.battle.result.dto.ActiveRoomResponse;
import com.back.domain.battle.result.dto.MyBattleResultsResponse;
import com.back.domain.member.member.repository.MemberRepository;
import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.problem.submission.repository.SubmissionRepository;
import com.back.global.exception.ServiceException;
import com.back.global.websocket.pubsub.WebSocketMessagePublisher;

class BattleResultServiceTest {

    private final BattleRoomRepository battleRoomRepository = mock(BattleRoomRepository.class);
    private final BattleParticipantRepository battleParticipantRepository = mock(BattleParticipantRepository.class);
    private final SubmissionRepository submissionRepository = mock(SubmissionRepository.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final WebSocketMessagePublisher publisher = mock(WebSocketMessagePublisher.class);

    private final BattleResultService battleResultService = new BattleResultService(
            battleRoomRepository, battleParticipantRepository, submissionRepository, memberRepository, publisher);

    @Test
    @DisplayName("내 전적 조회 성공 시 battleResults와 pageInfo를 반환한다")
    void getMyBattleResults_success() {
        // given
        Long memberId = 1L;
        int page = 0;
        int size = 20;

        BattleParticipant participant = mock(BattleParticipant.class);
        BattleRoom room = mock(BattleRoom.class);
        Problem problem = mock(Problem.class);

        LocalDateTime finishTime = LocalDateTime.of(2026, 3, 24, 20, 15, 20);
        LocalDateTime playedAt = LocalDateTime.of(2026, 3, 24, 20, 0, 0);

        when(participant.getBattleRoom()).thenReturn(room);
        when(participant.getFinalRank()).thenReturn(2);
        when(participant.getScoreDelta()).thenReturn(70L);
        when(participant.getStatus()).thenReturn(BattleParticipantStatus.EXIT);
        when(participant.getFinishTime()).thenReturn(finishTime);

        when(room.getId()).thenReturn(101L);
        when(room.getProblem()).thenReturn(problem);
        when(room.getCreatedAt()).thenReturn(playedAt);

        when(problem.getId()).thenReturn(5L);
        when(problem.getTitle()).thenReturn("Two Sum");

        Page<BattleParticipant> participantPage = new PageImpl<>(List.of(participant), PageRequest.of(0, 20), 1);

        when(battleParticipantRepository.findFinishedBattleResultsByMemberId(
                        eq(memberId), eq(BattleRoomStatus.FINISHED), org.mockito.ArgumentMatchers.any()))
                .thenReturn(participantPage);

        // when
        MyBattleResultsResponse response = battleResultService.getMyBattleResults(memberId, page, size);

        // then
        assertThat(response.battleResults()).hasSize(1);
        assertThat(response.pageInfo().page()).isEqualTo(0);
        assertThat(response.pageInfo().size()).isEqualTo(20);
        assertThat(response.pageInfo().totalElements()).isEqualTo(1);

        MyBattleResultsResponse.MyBattleResultItem item =
                response.battleResults().get(0);
        assertThat(item.roomId()).isEqualTo(101L);
        assertThat(item.problemId()).isEqualTo(5L);
        assertThat(item.problemTitle()).isEqualTo("Two Sum");
        assertThat(item.finalRank()).isEqualTo(2);
        assertThat(item.scoreDelta()).isEqualTo(70L);
        assertThat(item.solved()).isTrue();
        assertThat(item.finishTime()).isEqualTo(finishTime);
        assertThat(item.playedAt()).isEqualTo(playedAt);
    }

    @Test
    @DisplayName("memberId가 없으면 로그인 필요 예외를 던진다")
    void getMyBattleResults_fail_whenMemberIdIsNull() {
        assertThatThrownBy(() -> battleResultService.getMyBattleResults(null, 0, 20))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("로그인이 필요합니다.");
    }

    @Test
    @DisplayName("page가 0 미만이면 예외를 던진다")
    void getMyBattleResults_fail_whenPageIsNegative() {
        assertThatThrownBy(() -> battleResultService.getMyBattleResults(1L, -1, 20))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("page는 0 이상");
    }

    @Test
    @DisplayName("size가 1 미만이면 예외를 던진다")
    void getMyBattleResults_fail_whenSizeIsInvalid() {
        assertThatThrownBy(() -> battleResultService.getMyBattleResults(1L, 0, 0))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("size는 1 이상");
    }

    @Test
    @DisplayName("참여 중인 방(PLAYING 또는 ABANDONED)이 있으면 roomId를 반환한다")
    void getActiveRoom_whenExists() {
        // given
        Long memberId = 1L;

        BattleParticipant participant = mock(BattleParticipant.class);
        BattleRoom room = mock(BattleRoom.class);

        when(participant.getBattleRoom()).thenReturn(room);
        when(room.getId()).thenReturn(42L);
        when(battleParticipantRepository.findActiveParticipantByMemberId(memberId))
                .thenReturn(Optional.of(participant));

        // when
        ActiveRoomResponse response = battleResultService.getActiveRoom(memberId);

        // then
        assertThat(response).isNotNull();
        assertThat(response.roomId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("참여 중인 방이 없으면 null을 반환한다")
    void getActiveRoom_whenNotExists() {
        // given
        Long memberId = 1L;

        when(battleParticipantRepository.findActiveParticipantByMemberId(memberId))
                .thenReturn(Optional.empty());

        // when
        ActiveRoomResponse response = battleResultService.getActiveRoom(memberId);

        // then
        assertThat(response).isNull();
    }
}
