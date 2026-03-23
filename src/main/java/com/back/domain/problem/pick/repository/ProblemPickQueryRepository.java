package com.back.domain.problem.pick.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.back.domain.problem.problem.enums.DifficultyLevel;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
/**
 * 문제 출제 후보 조회 전용 리포지토리.
 *
 * <p>선정 로직은 서비스에서 담당하고, 이 클래스는 "조건에 맞는 후보 집합"을
 * count/offset 조회하는 쿼리 실행만 담당한다.
 */
public class ProblemPickQueryRepository {

    private final EntityManager entityManager;

    /**
     * 출제 조건을 만족하는 후보 문제 수를 반환한다.
     *
     * <p>랜덤 선정 전에 전체 후보 개수를 알아야 하므로 먼저 count를 수행한다.
     */
    public long countCandidates(DifficultyLevel difficulty, String normalizedCategory, List<Long> excludeProblemIds) {
        // exclude 목록 유무에 따라 JPQL을 나눠, 빈 IN 절로 인한 런타임 오류를 피한다.
        TypedQuery<Long> query = entityManager
                .createQuery(buildCountJpql(excludeProblemIds), Long.class)
                .setParameter("difficulty", difficulty)
                .setParameter("category", normalizedCategory);

        if (!excludeProblemIds.isEmpty()) {
            query.setParameter("excludeProblemIds", excludeProblemIds);
        }

        // offset으로 랜덤 접근할 때 전체 후보 수 계산에 사용한다.
        return query.getSingleResult();
    }

    public Optional<Long> findCandidateIdByOffset(
            DifficultyLevel difficulty, String normalizedCategory, List<Long> excludeProblemIds, long offset) {
        // count 결과에서 뽑은 offset 위치의 후보 1건을 조회한다.
        TypedQuery<Long> query = entityManager
                .createQuery(buildSelectJpql(excludeProblemIds), Long.class)
                .setParameter("difficulty", difficulty)
                .setParameter("category", normalizedCategory)
                .setFirstResult(Math.toIntExact(offset))
                .setMaxResults(1);

        if (!excludeProblemIds.isEmpty()) {
            query.setParameter("excludeProblemIds", excludeProblemIds);
        }

        // 지정된 오프셋에서 1건만 꺼내 문제 ID를 반환한다.
        return query.getResultList().stream().findFirst();
    }

    private String buildCountJpql(List<Long> excludeProblemIds) {
        // 같은 문제가 다중 태그로 조인될 수 있어 distinct로 중복 카운트를 방지한다.
        return "select count(distinct p.id) " + buildFromWhereJpql(excludeProblemIds)
                + buildExcludeClause(excludeProblemIds);
    }

    private String buildSelectJpql(List<Long> excludeProblemIds) {
        // offset 기반 조회의 재현성을 위해 id 순으로 정렬한다.
        return "select distinct p.id "
                + buildFromWhereJpql(excludeProblemIds)
                + buildExcludeClause(excludeProblemIds)
                + " order by p.id";
    }

    private String buildFromWhereJpql(List<Long> excludeProblemIds) {
        // 문제-태그 연결 테이블을 통해 category 필터를 적용한다.
        // 그리고 실전 채점 가능한 문제만 대상으로 하기 위해 hidden 테스트 존재를 강제한다.
        return """
                from Problem p
                join ProblemTagConnect ptc on ptc.problem = p
                join Tag t on ptc.tag = t
                where p.difficulty = :difficulty
                and lower(t.name) = :category
                  and exists (
                        select tc.id
                        from TestCase tc
                        where tc.problem = p
                          and tc.isSample = false
                  )
                """;
    }

    private String buildExcludeClause(List<Long> excludeProblemIds) {
        if (excludeProblemIds.isEmpty()) {
            // 제외 목록이 비어 있으면 where 절을 추가하지 않는다.
            return "";
        }

        // 제외 대상이 있는 경우만 NOT IN 조건을 붙인다.
        return " and p.id not in :excludeProblemIds";
    }
}
