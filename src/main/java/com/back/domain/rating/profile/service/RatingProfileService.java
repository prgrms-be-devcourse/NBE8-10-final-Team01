package com.back.domain.rating.profile.service;

import java.time.LocalDateTime;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.back.domain.member.member.entity.Member;
import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.rating.policy.TierPolicy;
import com.back.domain.rating.profile.entity.MemberRatingProfile;
import com.back.domain.rating.profile.repository.MemberRatingProfileRepository;
import com.back.domain.rating.solve.entity.FirstSolvedMode;
import com.back.domain.rating.solve.entity.MemberProblemFirstSolve;
import com.back.domain.rating.solve.repository.MemberProblemFirstSolveRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RatingProfileService {

    // tierScore = 배틀 누적 레이팅 + 문제별 first-AC 난이도 누적치
    private static final int BATTLE_WEIGHT = 1;

    // 문제 난이도 rating(예: 800~3500)을 first-AC 보너스 점수 단위로 축소해 누적한다.
    private static final int FIRST_SOLVE_DIFFICULTY_DIVISOR = 25;
    private static final int FIRST_SOLVE_DIFFICULTY_MIN_SCORE = 30;
    private static final int FIRST_SOLVE_DIFFICULTY_DEFAULT_SCORE = 30;

    private final MemberRatingProfileRepository memberRatingProfileRepository;
    private final MemberProblemFirstSolveRepository memberProblemFirstSolveRepository;

    @Transactional
    public void ensureProfile(Member member) {
        getOrCreateProfile(member);
    }

    @Transactional
    public void applyBattleResult(Member member, long scoreDelta) {
        MemberRatingProfile profile = getOrCreateProfile(member);

        // 기존 배틀 결과 점수(1등 +100 등)를 배틀 레이팅에 누적한다.
        profile.applyBattleRatingDelta(toIntScore(scoreDelta));
        // 해당 방에 참가해 정산된 횟수를 활동량 게이트로 사용한다.
        profile.increaseBattleMatchCount();

        refreshTier(profile);
    }

    @Transactional
    public boolean applySoloFirstSolve(Member member, Problem problem, LocalDateTime solvedAt) {
        return applyFirstSolve(member, problem, FirstSolvedMode.SOLO, solvedAt);
    }

    @Transactional
    public boolean applyBattleFirstSolve(Member member, Problem problem, LocalDateTime solvedAt) {
        return applyFirstSolve(member, problem, FirstSolvedMode.BATTLE, solvedAt);
    }

    private boolean applyFirstSolve(Member member, Problem problem, FirstSolvedMode mode, LocalDateTime solvedAt) {
        // 빠른 중복 차단: 이미 같은 문제의 first solve가 있으면 보상 없이 종료한다.
        if (memberProblemFirstSolveRepository.existsByMemberIdAndProblemId(member.getId(), problem.getId())) {
            return false;
        }

        try {
            MemberProblemFirstSolve firstSolve = MemberProblemFirstSolve.create(
                    member,
                    problem,
                    mode,
                    problem.getDifficultyRating(),
                    solvedAt != null ? solvedAt : LocalDateTime.now());
            memberProblemFirstSolveRepository.save(firstSolve);
        } catch (DataIntegrityViolationException ignored) {
            // 동시 제출 경합으로 유니크 제약에 걸린 경우에도 "이미 처리됨"으로 취급한다.
            return false;
        }

        MemberRatingProfile profile = getOrCreateProfile(member);
        // first-AC는 솔로/배틀 경로와 무관하게 같은 난이도 보너스 정책을 사용한다.
        profile.applyFirstSolveScoreDelta(toFirstSolveDifficultyScore(problem.getDifficultyRating()));
        profile.increaseFirstSolvedProblemCount();

        refreshTier(profile);
        return true;
    }

    private MemberRatingProfile getOrCreateProfile(Member member) {
        return memberRatingProfileRepository
                .findByMemberId(member.getId())
                .orElseGet(() -> memberRatingProfileRepository.save(MemberRatingProfile.createDefault(member)));
    }

    private void refreshTier(MemberRatingProfile profile) {
        int battleRating = profile.getBattleRating() == null ? 0 : profile.getBattleRating();
        int firstSolveScore = profile.getFirstSolveScore() == null ? 0 : profile.getFirstSolveScore();

        int tierScore = (battleRating * BATTLE_WEIGHT) + firstSolveScore;
        profile.updateTierScore(tierScore);
        profile.updateTier(TierPolicy.resolveTier(tierScore));
    }

    private int toFirstSolveDifficultyScore(Integer difficultyRating) {
        if (difficultyRating == null || difficultyRating <= 0) {
            return FIRST_SOLVE_DIFFICULTY_DEFAULT_SCORE;
        }

        return Math.max(FIRST_SOLVE_DIFFICULTY_MIN_SCORE, difficultyRating / FIRST_SOLVE_DIFFICULTY_DIVISOR);
    }

    private int toIntScore(long scoreDelta) {
        if (scoreDelta > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (scoreDelta < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) scoreDelta;
    }
}
