package com.back.domain.battle.result.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.back.domain.battle.battleparticipant.entity.BattleParticipant;
import com.back.domain.battle.battleparticipant.entity.BattleParticipantStatus;
import com.back.domain.battle.battleparticipant.repository.BattleParticipantRepository;
import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.battle.battleroom.entity.BattleRoomStatus;
import com.back.domain.battle.battleroom.repository.BattleRoomRepository;
import com.back.domain.battle.result.dto.ActiveRoomResponse;
import com.back.domain.battle.result.dto.MyBattleResultsResponse;
import com.back.domain.member.member.entity.Member;
import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.problem.submission.entity.SubmissionResult;
import com.back.domain.problem.submission.repository.SubmissionRepository;
import com.back.domain.rating.profile.service.RatingProfileService;
import com.back.global.exception.ServiceException;
import com.back.global.websocket.BattleCodeStore;
import com.back.global.websocket.pubsub.WebSocketMessagePublisher;

class BattleResultServiceTest {

    private final BattleRoomRepository battleRoomRepository = mock(BattleRoomRepository.class);
    private final BattleParticipantRepository battleParticipantRepository = mock(BattleParticipantRepository.class);
    private final SubmissionRepository submissionRepository = mock(SubmissionRepository.class);
    private final RatingProfileService ratingProfileService = mock(RatingProfileService.class);
    private final WebSocketMessagePublisher publisher = mock(WebSocketMessagePublisher.class);

    private final BattleResultService battleResultService = new BattleResultService(
            battleRoomRepository,
            battleParticipantRepository,
            submissionRepository,
            ratingProfileService,
            publisher,
            mock(BattleCodeStore.class));

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
        when(participant.getStatus()).thenReturn(BattleParticipantStatus.SOLVED);
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

    @Test
    @DisplayName("정산 시 ABANDONED 참여자는 QUIT과 동일하게 감점된다")
    void settle_abandonedParticipant_getsPenalty() {
        BattleRoom room = mock(BattleRoom.class);
        Problem problem = mock(Problem.class);
        BattleParticipant participant = mock(BattleParticipant.class);
        com.back.domain.member.member.entity.Member member = mock(com.back.domain.member.member.entity.Member.class);

        when(battleRoomRepository.findById(1L)).thenReturn(Optional.of(room));
        when(room.getStatus()).thenReturn(BattleRoomStatus.PLAYING);
        when(room.getProblem()).thenReturn(problem);
        when(problem.getDifficultyRating()).thenReturn(1200);
        when(battleParticipantRepository.findByBattleRoom(room)).thenReturn(List.of(participant));
        when(participant.getId()).thenReturn(11L);
        when(participant.getStatus()).thenReturn(BattleParticipantStatus.ABANDONED);
        when(participant.getMember()).thenReturn(member);
        when(member.getId()).thenReturn(101L);
        when(ratingProfileService.applyBattlePlacements(any(), eq(1200))).thenReturn(Map.of(101L, -10));

        withAfterCommit(() -> battleResultService.settle(1L));

        verify(participant).applyResult(1, -10L);
        verify(participant, never()).timeout();
    }

    @Test
    @DisplayName("정산 시 QUIT 참여자는 감점된다")
    void settle_quitParticipant_getsPenalty() {
        BattleRoom room = mock(BattleRoom.class);
        Problem problem = mock(Problem.class);
        BattleParticipant participant = mock(BattleParticipant.class);
        com.back.domain.member.member.entity.Member member = mock(com.back.domain.member.member.entity.Member.class);

        when(battleRoomRepository.findById(2L)).thenReturn(Optional.of(room));
        when(room.getStatus()).thenReturn(BattleRoomStatus.PLAYING);
        when(room.getProblem()).thenReturn(problem);
        when(problem.getDifficultyRating()).thenReturn(1200);
        when(battleParticipantRepository.findByBattleRoom(room)).thenReturn(List.of(participant));
        when(participant.getId()).thenReturn(22L);
        when(participant.getStatus()).thenReturn(BattleParticipantStatus.QUIT);
        when(participant.getMember()).thenReturn(member);
        when(member.getId()).thenReturn(202L);
        when(ratingProfileService.applyBattlePlacements(any(), eq(1200))).thenReturn(Map.of(202L, -10));

        withAfterCommit(() -> battleResultService.settle(2L));

        verify(participant).applyResult(1, -10L);
    }

    // -----------------------------------------------------------------------
    // 순위 산정 공정성 테스트
    // calcScore = 배틀 시작부터 finishTime까지의 초 + WA 횟수 * 20초 패널티
    // 미통과(TIMEOUT/ABANDONED/QUIT) 참여자는 Long.MAX_VALUE → 항상 AC 뒤
    // 동점이면 finishTime 빠른 사람이 앞 순위
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC 참여자는 미통과 참여자보다 앞 순위를 받는다")
    void settle_acRanksBeforeNonAc() {
        BattleRoom room = mock(BattleRoom.class);
        Problem problem = mock(Problem.class);
        Member memberA = mock(Member.class);
        Member memberB = mock(Member.class);
        BattleParticipant participantA = mock(BattleParticipant.class);
        BattleParticipant participantB = mock(BattleParticipant.class);

        LocalDateTime startedAt = LocalDateTime.of(2026, 1, 1, 10, 0, 0);

        when(battleRoomRepository.findById(1L)).thenReturn(Optional.of(room));
        when(room.getStatus()).thenReturn(BattleRoomStatus.PLAYING);
        when(room.getProblem()).thenReturn(problem);
        when(room.getStartedAt()).thenReturn(startedAt);
        when(problem.getDifficultyRating()).thenReturn(1200);
        when(battleParticipantRepository.findByBattleRoom(room)).thenReturn(List.of(participantA, participantB));

        when(participantA.getId()).thenReturn(1L);
        when(participantA.getStatus()).thenReturn(BattleParticipantStatus.SOLVED);
        when(participantA.getFinishTime()).thenReturn(startedAt.plusMinutes(10));
        when(participantA.getMember()).thenReturn(memberA);
        when(memberA.getId()).thenReturn(101L);

        when(participantB.getId()).thenReturn(2L);
        when(participantB.getStatus()).thenReturn(BattleParticipantStatus.TIMEOUT);
        when(participantB.getMember()).thenReturn(memberB);
        when(memberB.getId()).thenReturn(102L);

        when(submissionRepository.countByBattleRoomAndMemberAndResultAndCreatedAtBefore(
                        eq(room), eq(memberA), eq(SubmissionResult.WA), any()))
                .thenReturn(0L);
        when(ratingProfileService.applyBattlePlacements(any(), eq(1200))).thenReturn(Map.of());

        withAfterCommit(() -> battleResultService.settle(1L));

        verify(participantA).applyResult(1, 0L);
        verify(participantB).applyResult(2, 0L);
    }

    @Test
    @DisplayName("AC 참여자 간에는 finishTime 빠른 순으로 순위를 받는다")
    void settle_earlierFinishTimeWinsAmongAc() {
        BattleRoom room = mock(BattleRoom.class);
        Problem problem = mock(Problem.class);
        Member memberA = mock(Member.class);
        Member memberB = mock(Member.class);
        BattleParticipant participantA = mock(BattleParticipant.class); // 15분 - 늦게 풀음
        BattleParticipant participantB = mock(BattleParticipant.class); // 10분 - 먼저 풀음

        LocalDateTime startedAt = LocalDateTime.of(2026, 1, 1, 10, 0, 0);

        when(battleRoomRepository.findById(1L)).thenReturn(Optional.of(room));
        when(room.getStatus()).thenReturn(BattleRoomStatus.PLAYING);
        when(room.getProblem()).thenReturn(problem);
        when(room.getStartedAt()).thenReturn(startedAt);
        when(problem.getDifficultyRating()).thenReturn(1200);
        when(battleParticipantRepository.findByBattleRoom(room)).thenReturn(List.of(participantA, participantB));

        when(participantA.getId()).thenReturn(1L);
        when(participantA.getStatus()).thenReturn(BattleParticipantStatus.SOLVED);
        when(participantA.getFinishTime()).thenReturn(startedAt.plusMinutes(15)); // score = 900
        when(participantA.getMember()).thenReturn(memberA);
        when(memberA.getId()).thenReturn(101L);

        when(participantB.getId()).thenReturn(2L);
        when(participantB.getStatus()).thenReturn(BattleParticipantStatus.SOLVED);
        when(participantB.getFinishTime()).thenReturn(startedAt.plusMinutes(10)); // score = 600
        when(participantB.getMember()).thenReturn(memberB);
        when(memberB.getId()).thenReturn(102L);

        when(submissionRepository.countByBattleRoomAndMemberAndResultAndCreatedAtBefore(
                        eq(room), any(), eq(SubmissionResult.WA), any()))
                .thenReturn(0L);
        when(ratingProfileService.applyBattlePlacements(any(), eq(1200))).thenReturn(Map.of());

        withAfterCommit(() -> battleResultService.settle(1L));

        verify(participantB).applyResult(1, 0L); // 10분 → 1등
        verify(participantA).applyResult(2, 0L); // 15분 → 2등
    }

    @Test
    @DisplayName("WA 패널티(1회당 20초)가 score에 반영되어 순위가 바뀐다")
    void settle_waPenaltyAppliedToScore() {
        BattleRoom room = mock(BattleRoom.class);
        Problem problem = mock(Problem.class);
        Member memberA = mock(Member.class);
        Member memberB = mock(Member.class);
        BattleParticipant participantA = mock(BattleParticipant.class); // 9분 + WA 1회 = 540 + 20 = 560초
        BattleParticipant participantB = mock(BattleParticipant.class); // 10분 + WA 0회 = 600초

        LocalDateTime startedAt = LocalDateTime.of(2026, 1, 1, 10, 0, 0);
        LocalDateTime finishA = startedAt.plusSeconds(540); // 9분
        LocalDateTime finishB = startedAt.plusSeconds(600); // 10분

        when(battleRoomRepository.findById(1L)).thenReturn(Optional.of(room));
        when(room.getStatus()).thenReturn(BattleRoomStatus.PLAYING);
        when(room.getProblem()).thenReturn(problem);
        when(room.getStartedAt()).thenReturn(startedAt);
        when(problem.getDifficultyRating()).thenReturn(1200);
        when(battleParticipantRepository.findByBattleRoom(room)).thenReturn(List.of(participantA, participantB));

        when(participantA.getId()).thenReturn(1L);
        when(participantA.getStatus()).thenReturn(BattleParticipantStatus.SOLVED);
        when(participantA.getFinishTime()).thenReturn(finishA);
        when(participantA.getMember()).thenReturn(memberA);
        when(memberA.getId()).thenReturn(101L);

        when(participantB.getId()).thenReturn(2L);
        when(participantB.getStatus()).thenReturn(BattleParticipantStatus.SOLVED);
        when(participantB.getFinishTime()).thenReturn(finishB);
        when(participantB.getMember()).thenReturn(memberB);
        when(memberB.getId()).thenReturn(102L);

        // A: WA 1회 → score = 540 + 20 = 560
        when(submissionRepository.countByBattleRoomAndMemberAndResultAndCreatedAtBefore(
                        eq(room), eq(memberA), eq(SubmissionResult.WA), eq(finishA)))
                .thenReturn(1L);
        // B: WA 0회 → score = 600
        when(submissionRepository.countByBattleRoomAndMemberAndResultAndCreatedAtBefore(
                        eq(room), eq(memberB), eq(SubmissionResult.WA), eq(finishB)))
                .thenReturn(0L);
        when(ratingProfileService.applyBattlePlacements(any(), eq(1200))).thenReturn(Map.of());

        withAfterCommit(() -> battleResultService.settle(1L));

        verify(participantA).applyResult(1, 0L); // 560초 → 1등
        verify(participantB).applyResult(2, 0L); // 600초 → 2등
    }

    @Test
    @DisplayName("WA 패널티가 누적되면 더 빨리 푼 사람도 순위가 밀릴 수 있다")
    void settle_heavyWaPenaltyCanFlipRanking() {
        BattleRoom room = mock(BattleRoom.class);
        Problem problem = mock(Problem.class);
        Member memberA = mock(Member.class);
        Member memberB = mock(Member.class);
        BattleParticipant participantA = mock(BattleParticipant.class); // 10분 + WA 3회 = 600 + 60 = 660초
        BattleParticipant participantB = mock(BattleParticipant.class); // 10분 50초 + WA 0회 = 650초

        LocalDateTime startedAt = LocalDateTime.of(2026, 1, 1, 10, 0, 0);
        LocalDateTime finishA = startedAt.plusSeconds(600); // 10분
        LocalDateTime finishB = startedAt.plusSeconds(650); // 10분 50초

        when(battleRoomRepository.findById(1L)).thenReturn(Optional.of(room));
        when(room.getStatus()).thenReturn(BattleRoomStatus.PLAYING);
        when(room.getProblem()).thenReturn(problem);
        when(room.getStartedAt()).thenReturn(startedAt);
        when(problem.getDifficultyRating()).thenReturn(1200);
        when(battleParticipantRepository.findByBattleRoom(room)).thenReturn(List.of(participantA, participantB));

        when(participantA.getId()).thenReturn(1L);
        when(participantA.getStatus()).thenReturn(BattleParticipantStatus.SOLVED);
        when(participantA.getFinishTime()).thenReturn(finishA);
        when(participantA.getMember()).thenReturn(memberA);
        when(memberA.getId()).thenReturn(101L);

        when(participantB.getId()).thenReturn(2L);
        when(participantB.getStatus()).thenReturn(BattleParticipantStatus.SOLVED);
        when(participantB.getFinishTime()).thenReturn(finishB);
        when(participantB.getMember()).thenReturn(memberB);
        when(memberB.getId()).thenReturn(102L);

        // A: WA 3회 → score = 600 + 60 = 660
        when(submissionRepository.countByBattleRoomAndMemberAndResultAndCreatedAtBefore(
                        eq(room), eq(memberA), eq(SubmissionResult.WA), eq(finishA)))
                .thenReturn(3L);
        // B: WA 0회 → score = 650
        when(submissionRepository.countByBattleRoomAndMemberAndResultAndCreatedAtBefore(
                        eq(room), eq(memberB), eq(SubmissionResult.WA), eq(finishB)))
                .thenReturn(0L);
        when(ratingProfileService.applyBattlePlacements(any(), eq(1200))).thenReturn(Map.of());

        withAfterCommit(() -> battleResultService.settle(1L));

        verify(participantB).applyResult(1, 0L); // 650초 → 1등
        verify(participantA).applyResult(2, 0L); // 660초 → 2등
    }

    @Test
    @DisplayName("score가 같으면 finishTime이 빠른 참여자가 앞 순위를 받는다")
    void settle_tieScoreBreaksByFinishTime() {
        BattleRoom room = mock(BattleRoom.class);
        Problem problem = mock(Problem.class);
        Member memberA = mock(Member.class);
        Member memberB = mock(Member.class);
        BattleParticipant participantA = mock(BattleParticipant.class);
        BattleParticipant participantB = mock(BattleParticipant.class);

        LocalDateTime startedAt = LocalDateTime.of(2026, 1, 1, 10, 0, 0);
        // 둘 다 정확히 600초 경과 → elapsedSeconds 동일, finishTime 차이로 순위 결정
        LocalDateTime finishA = startedAt.plusSeconds(600);
        LocalDateTime finishB = startedAt.plusSeconds(600).plusNanos(500_000_000); // 0.5초 늦음

        when(battleRoomRepository.findById(1L)).thenReturn(Optional.of(room));
        when(room.getStatus()).thenReturn(BattleRoomStatus.PLAYING);
        when(room.getProblem()).thenReturn(problem);
        when(room.getStartedAt()).thenReturn(startedAt);
        when(problem.getDifficultyRating()).thenReturn(1200);
        when(battleParticipantRepository.findByBattleRoom(room)).thenReturn(List.of(participantA, participantB));

        when(participantA.getId()).thenReturn(1L);
        when(participantA.getStatus()).thenReturn(BattleParticipantStatus.SOLVED);
        when(participantA.getFinishTime()).thenReturn(finishA);
        when(participantA.getMember()).thenReturn(memberA);
        when(memberA.getId()).thenReturn(101L);

        when(participantB.getId()).thenReturn(2L);
        when(participantB.getStatus()).thenReturn(BattleParticipantStatus.SOLVED);
        when(participantB.getFinishTime()).thenReturn(finishB);
        when(participantB.getMember()).thenReturn(memberB);
        when(memberB.getId()).thenReturn(102L);

        when(submissionRepository.countByBattleRoomAndMemberAndResultAndCreatedAtBefore(
                        eq(room), any(), eq(SubmissionResult.WA), any()))
                .thenReturn(0L);
        when(ratingProfileService.applyBattlePlacements(any(), eq(1200))).thenReturn(Map.of());

        withAfterCommit(() -> battleResultService.settle(1L));

        verify(participantA).applyResult(1, 0L); // finishTime 빠름 → 1등
        verify(participantB).applyResult(2, 0L); // finishTime 0.5초 늦음 → 2등
    }

    private void withAfterCommit(Runnable action) {
        try (MockedStatic<TransactionSynchronizationManager> mocked =
                mockStatic(TransactionSynchronizationManager.class)) {
            mocked.when(() -> TransactionSynchronizationManager.registerSynchronization(
                            any(TransactionSynchronization.class)))
                    .thenAnswer(invocation -> {
                        TransactionSynchronization synchronization =
                                invocation.getArgument(0, TransactionSynchronization.class);
                        synchronization.afterCommit();
                        return null;
                    });
            action.run();
        }
    }
}
