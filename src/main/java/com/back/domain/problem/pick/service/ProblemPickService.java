package com.back.domain.problem.pick.service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.back.domain.problem.pick.dto.ProblemPickRequest;
import com.back.domain.problem.pick.repository.ProblemPickQueryRepository;
import com.back.domain.problem.problem.enums.DifficultyLevel;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProblemPickService {

    private static final String NO_PROBLEM_MESSAGE = "출제 가능한 문제가 없습니다.";
    private static final String TOO_MANY_CANDIDATES_MESSAGE = "출제 후보 수가 너무 커 오프셋 계산이 불가능합니다.";

    private final ProblemPickQueryRepository problemPickQueryRepository;

    /**
     * 큐 연동용 단축 메서드.
     * exclude 조건 없이 기본 출제 규칙으로 1문제를 고른다.
     */
    public Long pickProblemId(String category, DifficultyLevel difficulty, List<Long> participantIds) {
        return pickProblemId(new ProblemPickRequest(category, difficulty, participantIds, List.of()));
    }

    /**
     * 출제 규칙으로 문제 1개를 선정한다.
     *
     * 1) difficulty/category 검증 및 정규화
     * 2) exclude 목록을 적용해 후보 수 조회
     * 3) 후보가 없고 exclude가 있으면 exclude를 풀고 1회 재시도
     * 4) 후보 수 기반 random offset으로 1건 선택
     */
    public Long pickProblemId(ProblemPickRequest request) {
        DifficultyLevel difficulty = requireDifficulty(request.difficulty());
        String normalizedCategory = normalizeCategory(request.category());

        // participantIds는 추후 개인화/중복회피 룰 확장 시 사용할 입력으로 유지한다.
        List<Long> excludeProblemIds = request.excludeProblemIds();

        long candidateCount =
                problemPickQueryRepository.countCandidates(difficulty, normalizedCategory, excludeProblemIds);

        if (candidateCount == 0 && !excludeProblemIds.isEmpty()) {
            // 제외 조건 때문에만 0건인 상황을 완화하기 위해 1회 fallback을 허용한다.
            excludeProblemIds = List.of();
            candidateCount =
                    problemPickQueryRepository.countCandidates(difficulty, normalizedCategory, excludeProblemIds);
        }

        if (candidateCount == 0) {
            throw new IllegalStateException(NO_PROBLEM_MESSAGE);
        }

        if (candidateCount > Integer.MAX_VALUE) {
            throw new IllegalStateException(TOO_MANY_CANDIDATES_MESSAGE + " candidateCount=" + candidateCount);
        }

        // ORDER BY random() 대신 offset 랜덤 방식으로 1건을 고른다.
        int randomOffset = ThreadLocalRandom.current().nextInt((int) candidateCount);

        return problemPickQueryRepository
                .findCandidateIdByOffset(difficulty, normalizedCategory, excludeProblemIds, randomOffset)
                .orElseThrow(() -> new IllegalStateException(NO_PROBLEM_MESSAGE));
    }

    private DifficultyLevel requireDifficulty(DifficultyLevel difficulty) {
        if (difficulty == null) {
            throw new IllegalArgumentException("난이도는 필수입니다.");
        }
        return difficulty;
    }

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("카테고리는 필수입니다.");
        }
        return category.trim().toLowerCase();
    }
}
