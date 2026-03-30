package com.back.domain.problem.problem.dto;

import java.util.List;

import com.back.domain.problem.problem.entity.Problem;

public record ProblemDetailResponse(
        Long problemId,
        String title,
        String difficulty,
        String content,
        String inputFormat,
        String outputFormat,
        Long timeLimitMs,
        Long memoryLimitMb,
        // 문제 화면에서 언어 선택 드롭다운을 구성하기 위한 지원 언어 목록
        List<String> supportedLanguages,
        // 언어 선택 초기값(없으면 프론트에서 첫 번째 언어를 fallback으로 사용)
        String defaultLanguage,
        // 언어별 시작 코드. 에디터 초기값 세팅에 사용
        List<StarterCode> starterCodes,
        // 공개 가능한 샘플 테스트 케이스만 전달
        List<SampleCase> sampleCases) {

    // 특정 언어의 기본 코드 템플릿
    public record StarterCode(String language, String code) {}

    // 문제 상세 화면 하단 예제 입/출력 영역 데이터
    public record SampleCase(String input, String output) {}

    public static ProblemDetailResponse from(
            Problem problem,
            List<String> supportedLanguages,
            String defaultLanguage,
            List<StarterCode> starterCodes,
            List<SampleCase> sampleCases) {
        return new ProblemDetailResponse(
                problem.getId(),
                problem.getTitle(),
                problem.getDifficulty().name(),
                problem.getContent(),
                problem.getInputFormat(),
                problem.getOutputFormat(),
                problem.getTimeLimitMs(),
                problem.getMemoryLimitMb(),
                supportedLanguages,
                defaultLanguage,
                starterCodes,
                sampleCases);
    }
}
