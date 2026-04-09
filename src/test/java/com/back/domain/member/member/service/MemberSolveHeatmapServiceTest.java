package com.back.domain.member.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.back.domain.battle.battleparticipant.repository.BattleParticipantRepository;
import com.back.domain.member.member.dto.SolveHeatmapResponse;
import com.back.domain.problem.solo.submission.repository.SoloSubmissionRepository;
import com.back.domain.problem.submission.entity.SubmissionResult;
import com.back.global.rsData.RsData;

class MemberSolveHeatmapServiceTest {

    private final SoloSubmissionRepository soloSubmissionRepository = mock(SoloSubmissionRepository.class);
    private final BattleParticipantRepository battleParticipantRepository = mock(BattleParticipantRepository.class);

    private final MemberSolveHeatmapService memberSolveHeatmapService =
            new MemberSolveHeatmapService(soloSubmissionRepository, battleParticipantRepository);

    @Test
    @DisplayName("Missing year defaults to the current year and returns an empty zero grid when no data exists.")
    void getMySolveHeatmap_defaultsToCurrentYearWithEmptyGrid() {
        when(soloSubmissionRepository.findFirstAcTimesByMemberIdAndResult(1L, SubmissionResult.AC))
                .thenReturn(List.of());
        when(battleParticipantRepository.findFirstSolvedAtByMemberIdAndStatus(
                        1L, com.back.domain.battle.battleparticipant.entity.BattleParticipantStatus.SOLVED))
                .thenReturn(List.of());

        RsData<SolveHeatmapResponse> result = memberSolveHeatmapService.getMySolveHeatmap(1L, null);

        assertThat(result.resultCode()).isEqualTo("200");
        assertThat(result.data().year()).isEqualTo(LocalDate.now().getYear());
        assertThat(result.data().availableYears())
                .containsExactly(LocalDate.now().getYear());
        assertThat(result.data().totalSolvedCount()).isZero();
        assertThat(result.data().maxDailySolvedCount()).isZero();
        assertThat(result.data().weeks()).isNotEmpty();
        assertThat(result.data().weeks())
                .allSatisfy(week -> assertThat(week.days()).hasSize(7));
        assertThat(result.data().weeks().stream()
                        .flatMap(week -> week.days().stream())
                        .filter(SolveHeatmapResponse.Day::inSelectedYear)
                        .count())
                .isEqualTo(Year.of(LocalDate.now().getYear()).length());
        assertThat(result.data().weeks().stream()
                        .flatMap(week -> week.days().stream())
                        .allMatch(day -> day.level() == 0))
                .isTrue();
    }

    @Test
    @DisplayName("Solo and battle solves on the same day are counted separately and summed in the tooltip total.")
    void getMySolveHeatmap_separatesSoloAndBattleCounts() {
        when(soloSubmissionRepository.findFirstAcTimesByMemberIdAndResult(2L, SubmissionResult.AC))
                .thenReturn(List.of(LocalDateTime.of(2026, 4, 9, 10, 0)));
        when(battleParticipantRepository.findFirstSolvedAtByMemberIdAndStatus(
                        2L, com.back.domain.battle.battleparticipant.entity.BattleParticipantStatus.SOLVED))
                .thenReturn(List.of(LocalDateTime.of(2026, 4, 9, 20, 0)));

        SolveHeatmapResponse response =
                memberSolveHeatmapService.getMySolveHeatmap(2L, 2026).data();
        SolveHeatmapResponse.Day day = findDay(response, "2026-04-09");

        assertThat(response.totalSolvedCount()).isEqualTo(2);
        assertThat(response.maxDailySolvedCount()).isEqualTo(2);
        assertThat(day.soloSolvedCount()).isEqualTo(1);
        assertThat(day.battleSolvedCount()).isEqualTo(1);
        assertThat(day.totalSolvedCount()).isEqualTo(2);
        assertThat(day.level()).isEqualTo(2);
        assertThat(day.inSelectedYear()).isTrue();
    }

    @Test
    @DisplayName("Relative levels scale against the maximum daily solve count when it is above four.")
    void getMySolveHeatmap_usesRelativeLevelsWhenMaxExceedsFour() {
        when(soloSubmissionRepository.findFirstAcTimesByMemberIdAndResult(3L, SubmissionResult.AC))
                .thenReturn(List.of(
                        LocalDateTime.of(2026, 4, 9, 9, 0),
                        LocalDateTime.of(2026, 4, 9, 10, 0),
                        LocalDateTime.of(2026, 4, 9, 11, 0),
                        LocalDateTime.of(2026, 4, 9, 12, 0),
                        LocalDateTime.of(2026, 4, 9, 13, 0),
                        LocalDateTime.of(2026, 4, 9, 14, 0),
                        LocalDateTime.of(2026, 4, 9, 15, 0),
                        LocalDateTime.of(2026, 4, 9, 16, 0),
                        LocalDateTime.of(2026, 4, 10, 10, 0),
                        LocalDateTime.of(2026, 4, 10, 11, 0),
                        LocalDateTime.of(2026, 4, 10, 12, 0),
                        LocalDateTime.of(2026, 4, 10, 13, 0)));
        when(battleParticipantRepository.findFirstSolvedAtByMemberIdAndStatus(
                        3L, com.back.domain.battle.battleparticipant.entity.BattleParticipantStatus.SOLVED))
                .thenReturn(List.of());

        SolveHeatmapResponse response =
                memberSolveHeatmapService.getMySolveHeatmap(3L, 2026).data();

        assertThat(response.maxDailySolvedCount()).isEqualTo(8);
        assertThat(findDay(response, "2026-04-09").level()).isEqualTo(4);
        assertThat(findDay(response, "2026-04-10").level()).isEqualTo(2);
    }

    @Test
    @DisplayName("When the maximum daily solve count is four or less, the count maps directly to the level.")
    void getMySolveHeatmap_usesDirectLevelsWhenMaxIsFourOrLess() {
        when(soloSubmissionRepository.findFirstAcTimesByMemberIdAndResult(4L, SubmissionResult.AC))
                .thenReturn(List.of(
                        LocalDateTime.of(2026, 4, 9, 9, 0),
                        LocalDateTime.of(2026, 4, 9, 10, 0),
                        LocalDateTime.of(2026, 4, 9, 11, 0),
                        LocalDateTime.of(2026, 4, 10, 10, 0)));
        when(battleParticipantRepository.findFirstSolvedAtByMemberIdAndStatus(
                        4L, com.back.domain.battle.battleparticipant.entity.BattleParticipantStatus.SOLVED))
                .thenReturn(List.of());

        SolveHeatmapResponse response =
                memberSolveHeatmapService.getMySolveHeatmap(4L, 2026).data();

        assertThat(response.maxDailySolvedCount()).isEqualTo(3);
        assertThat(findDay(response, "2026-04-09").level()).isEqualTo(3);
        assertThat(findDay(response, "2026-04-10").level()).isEqualTo(1);
    }

    private SolveHeatmapResponse.Day findDay(SolveHeatmapResponse response, String date) {
        return response.weeks().stream()
                .flatMap(week -> week.days().stream())
                .filter(day -> day.date().equals(date))
                .findFirst()
                .orElseThrow();
    }
}
