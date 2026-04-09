package com.back.domain.member.member.dto;

import java.util.List;

// 마이페이지 잔디 히트맵을 그리기 위한 최종 응답 DTO
public record SolveHeatmapResponse(
        int year,
        List<Integer> availableYears,
        int totalSolvedCount,
        int maxDailySolvedCount,
        List<MonthLabel> monthLabels,
        List<Week> weeks) {

    // 각 월 라벨이 몇 번째 주 컬럼부터 시작하는지 알려준다.
    public record MonthLabel(int month, String label, int weekIndex) {}

    // 프론트는 weeks -> days 구조를 그대로 순회해서 잔디 그리드를 그리면 된다.
    public record Week(String weekStartDate, List<Day> days) {}

    // 셀 하나에 필요한 카운트/색상/툴팁 정보를 모두 담는다.
    public record Day(
            String date,
            int soloSolvedCount,
            int battleSolvedCount,
            int totalSolvedCount,
            int level,
            boolean inSelectedYear,
            boolean isToday) {}
}
