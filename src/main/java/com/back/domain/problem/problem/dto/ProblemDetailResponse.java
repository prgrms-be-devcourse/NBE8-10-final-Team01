package com.back.domain.problem.problem.dto;

import java.util.List;

import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.problem.translation.entity.ProblemTranslation;

public record ProblemDetailResponse(
        Long problemId,
        String title,
        String difficulty,
        String content,
        String inputFormat,
        String outputFormat,
        Long timeLimitMs,
        Long memoryLimitMb,
        String language,
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
            ProblemTranslation translation,
            String language,
            List<String> supportedLanguages,
            String defaultLanguage,
            List<StarterCode> starterCodes,
            List<SampleCase> sampleCases) {
        return new ProblemDetailResponse(
                problem.getId(),
                getTranslatedOrOriginal(
                        translation != null ? translation.getTitle() : null,
                        problem.getTitle()
                ),
                problem.getDifficulty().name(),
                getTranslatedOrOriginal(
                        translation != null ? translation.getContent() : null,
                        problem.getContent()
                ),
                getTranslatedOrOriginal(
                        translation != null ? translation.getInputFormat() : null,
                        problem.getInputFormat()
                ),
                getTranslatedOrOriginal(
                        translation != null ? translation.getOutputFormat() : null,
                        problem.getOutputFormat()
                ),
                problem.getTimeLimitMs(),
                problem.getMemoryLimitMb(),
                language,
                supportedLanguages,
                defaultLanguage,
                starterCodes,
                sampleCases);
    }

    private static String getTranslatedOrOriginal(String translatedValue, String originalValue) {
        if (translatedValue == null || translatedValue.isBlank()) {
            return originalValue;
        }
        return translatedValue;
    }
}