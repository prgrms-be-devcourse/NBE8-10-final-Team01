package com.back.domain.member.member.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.stereotype.Service;

import com.back.domain.battle.battleparticipant.entity.BattleParticipantStatus;
import com.back.domain.battle.battleparticipant.repository.BattleParticipantRepository;
import com.back.domain.member.member.dto.SolveHeatmapResponse;
import com.back.domain.problem.solo.submission.repository.SoloSubmissionRepository;
import com.back.domain.problem.submission.entity.SubmissionResult;
import com.back.global.exception.ServiceException;
import com.back.global.rsData.RsData;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MemberSolveHeatmapService {

    // 잔디 그리드는 GitHub 스타일에 맞춰 일요일 시작, 토요일 종료로 계산한다.
    private static final DayOfWeek WEEK_START = DayOfWeek.SUNDAY;
    private static final DayOfWeek WEEK_END = DayOfWeek.SATURDAY;

    private final SoloSubmissionRepository soloSubmissionRepository;
    private final BattleParticipantRepository battleParticipantRepository;

    // 사용자의 특정 연도 잔디 데이터를 계산해서 프론트가 바로 그릴 수 있는 형태로 반환한다.
    public RsData<SolveHeatmapResponse> getMySolveHeatmap(Long memberId, Integer year) {
        if (memberId == null || memberId <= 0) {
            throw new ServiceException("MEMBER_400", "유효한 회원 ID가 필요합니다.");
        }

        int selectedYear = resolveYear(year);
        // 날짜별로 solo/battle 카운트를 누적할 맵이다.
        Map<LocalDate, DailySolveCount> countsByDate = new TreeMap<>();
        // 잔디에서 이동 가능한 연도 목록도 같이 만든다.
        NavigableSet<Integer> availableYears = new TreeSet<>(Comparator.reverseOrder());

        // solo는 문제별 최초 AC 1건만 가져와서 날짜별 카운트에 반영한다.
        mergeSolveDates(
                countsByDate,
                availableYears,
                soloSubmissionRepository.findFirstAcTimesByMemberIdAndResult(memberId, SubmissionResult.AC),
                SolveMode.SOLO);
        // battle도 문제별 최초 solve 1건만 가져와서 별도 카운트한다.
        mergeSolveDates(
                countsByDate,
                availableYears,
                battleParticipantRepository.findFirstSolvedAtByMemberIdAndStatus(
                        memberId, BattleParticipantStatus.SOLVED),
                SolveMode.BATTLE);

        // 선택 연도에 데이터가 없어도 빈 잔디를 보여주기 위해 목록에는 넣어둔다.
        availableYears.add(selectedYear);

        // 선택 연도 안의 totalSolvedCount만 합산해서 상단 요약값을 만든다.
        int totalSolvedCount = countsByDate.entrySet().stream()
                .filter(entry -> entry.getKey().getYear() == selectedYear)
                .mapToInt(entry -> entry.getValue().totalSolvedCount())
                .sum();

        // 선택 연도에서 가장 많이 푼 하루의 개수. 상대 레벨 계산에 사용한다.
        int maxDailySolvedCount = countsByDate.entrySet().stream()
                .filter(entry -> entry.getKey().getYear() == selectedYear)
                .mapToInt(entry -> entry.getValue().totalSolvedCount())
                .max()
                .orElse(0);

        LocalDate today = LocalDate.now();
        LocalDate firstDayOfYear = LocalDate.of(selectedYear, 1, 1);
        LocalDate lastDayOfYear = LocalDate.of(selectedYear, 12, 31);
        // 1월 1일이 주중에 있어도 앞쪽 패딩 칸을 포함해 시작 주를 맞춘다.
        LocalDate gridStart = firstDayOfYear.with(TemporalAdjusters.previousOrSame(WEEK_START));
        // 12월 31일도 마지막 주 끝까지 패딩 칸을 포함한다.
        LocalDate gridEnd = lastDayOfYear.with(TemporalAdjusters.nextOrSame(WEEK_END));

        SolveHeatmapResponse response = new SolveHeatmapResponse(
                selectedYear,
                List.copyOf(availableYears),
                totalSolvedCount,
                maxDailySolvedCount,
                buildMonthLabels(selectedYear, gridStart),
                buildWeeks(gridStart, gridEnd, selectedYear, today, countsByDate, maxDailySolvedCount));

        return RsData.of("200", "풀이 잔디 조회 성공", response);
    }

    // year 파라미터가 없으면 현재 연도를 기본값으로 사용한다.
    private int resolveYear(Integer year) {
        int resolvedYear = year != null ? year : LocalDate.now().getYear();
        if (resolvedYear < 1 || resolvedYear > 9999) {
            throw new ServiceException("MEMBER_400", "유효한 year 값이 필요합니다.");
        }
        return resolvedYear;
    }

    // 최초 풀이 시각 목록을 받아 날짜별 잔디 카운트로 합친다.
    private void mergeSolveDates(
            Map<LocalDate, DailySolveCount> countsByDate,
            NavigableSet<Integer> availableYears,
            List<LocalDateTime> dateTimes,
            SolveMode solveMode) {
        if (dateTimes == null) {
            return;
        }

        for (LocalDateTime dateTime : dateTimes) {
            if (dateTime == null) {
                continue;
            }

            // 시각은 버리고, 어느 날짜에 풀었는지만 남긴다.
            LocalDate date = dateTime.toLocalDate();
            availableYears.add(date.getYear());
            countsByDate.computeIfAbsent(date, ignored -> new DailySolveCount()).increment(solveMode);
        }
    }

    // 날짜별 카운트를 프론트가 바로 그릴 수 있는 주 단위 배열 구조로 변환한다.
    private List<SolveHeatmapResponse.Week> buildWeeks(
            LocalDate gridStart,
            LocalDate gridEnd,
            int selectedYear,
            LocalDate today,
            Map<LocalDate, DailySolveCount> countsByDate,
            int maxDailySolvedCount) {
        List<SolveHeatmapResponse.Week> weeks = new ArrayList<>();

        for (LocalDate weekStart = gridStart; !weekStart.isAfter(gridEnd); weekStart = weekStart.plusWeeks(1)) {
            List<SolveHeatmapResponse.Day> days = new ArrayList<>(7);

            for (int offset = 0; offset < 7; offset++) {
                LocalDate date = weekStart.plusDays(offset);
                // 선택한 연도 바깥의 패딩 칸은 항상 빈 셀처럼 보이게 0으로 처리한다.
                boolean inSelectedYear = date.getYear() == selectedYear;
                DailySolveCount dailySolveCount =
                        inSelectedYear ? countsByDate.getOrDefault(date, DailySolveCount.EMPTY) : DailySolveCount.EMPTY;
                int totalSolvedCount = dailySolveCount.totalSolvedCount();

                days.add(new SolveHeatmapResponse.Day(
                        date.toString(),
                        dailySolveCount.soloSolvedCount(),
                        dailySolveCount.battleSolvedCount(),
                        totalSolvedCount,
                        resolveLevel(totalSolvedCount, maxDailySolvedCount),
                        inSelectedYear,
                        date.equals(today)));
            }

            weeks.add(new SolveHeatmapResponse.Week(weekStart.toString(), days));
        }

        return weeks;
    }

    // 각 월 라벨이 몇 번째 주에 걸치는지 계산해 프론트에 전달한다.
    private List<SolveHeatmapResponse.MonthLabel> buildMonthLabels(int selectedYear, LocalDate gridStart) {
        List<SolveHeatmapResponse.MonthLabel> monthLabels = new ArrayList<>(12);

        for (Month month : Month.values()) {
            LocalDate monthStart = LocalDate.of(selectedYear, month, 1);
            LocalDate monthWeekStart = monthStart.with(TemporalAdjusters.previousOrSame(WEEK_START));
            int weekIndex = (int) ChronoUnit.WEEKS.between(gridStart, monthWeekStart);

            monthLabels.add(new SolveHeatmapResponse.MonthLabel(
                    month.getValue(),
                    month.name().substring(0, 1) + month.name().substring(1, 3).toLowerCase(),
                    weekIndex));
        }

        return monthLabels;
    }

    // totalSolvedCount를 잔디 색상 단계(level 0~4)로 변환한다.
    private int resolveLevel(int totalSolvedCount, int maxDailySolvedCount) {
        // 풀이가 없으면 가장 연한 단계다.
        if (totalSolvedCount <= 0) {
            return 0;
        }

        // 연간 최대 풀이 수가 4 이하이면 count를 그대로 level처럼 사용한다.
        if (maxDailySolvedCount <= 4) {
            return Math.min(totalSolvedCount, 4);
        }

        // 최대 풀이 수가 더 크면, 최대값을 4단계로 나눠 상대 레벨을 만든다.
        return Math.max(1, Math.min(4, (int) Math.ceil((double) totalSolvedCount * 4 / maxDailySolvedCount)));
    }

    // 내부 계산에서 solo/battle을 구분하기 위한 enum
    private enum SolveMode {
        SOLO,
        BATTLE
    }

    // 날짜 하나에 대해 solo/battle/total 카운트를 누적하기 위한 내부 객체
    private static final class DailySolveCount {
        private static final DailySolveCount EMPTY = new DailySolveCount();

        private int soloSolvedCount;
        private int battleSolvedCount;

        // 같은 날짜에 어떤 모드 풀이가 있었는지에 따라 해당 카운트를 1 증가시킨다.
        private void increment(SolveMode solveMode) {
            if (solveMode == SolveMode.SOLO) {
                soloSolvedCount++;
                return;
            }

            battleSolvedCount++;
        }

        private int soloSolvedCount() {
            return soloSolvedCount;
        }

        private int battleSolvedCount() {
            return battleSolvedCount;
        }

        private int totalSolvedCount() {
            return soloSolvedCount + battleSolvedCount;
        }
    }
}
