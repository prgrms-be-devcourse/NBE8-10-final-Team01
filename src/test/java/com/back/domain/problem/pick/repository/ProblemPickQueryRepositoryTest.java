package com.back.domain.problem.pick.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.back.domain.problem.problem.enums.DifficultyLevel;

@SpringBootTest
@ActiveProfiles("test")
class ProblemPickQueryRepositoryTest {

    @Autowired
    private ProblemPickQueryRepository problemPickQueryRepository;

    @Test
    @DisplayName("출제 후보가 없을 때 count는 0이고 조회 결과는 비어 있다")
    void queryRuns_whenNoCandidates() {
        // when
        long count = problemPickQueryRepository.countCandidates(DifficultyLevel.EASY, "array", List.of());

        // then
        assertThat(count).isZero();
        assertThat(problemPickQueryRepository.findCandidateIdByOffset(DifficultyLevel.EASY, "array", List.of(), 0))
                .isEmpty();
    }
}
