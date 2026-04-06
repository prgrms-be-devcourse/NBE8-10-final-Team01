package com.back.domain.rating.profile.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.back.domain.battle.battleparticipant.repository.BattleParticipantRepository;
import com.back.domain.member.member.entity.Member;
import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.rating.policy.TierPolicy;
import com.back.domain.rating.profile.entity.MemberRatingProfile;
import com.back.domain.rating.profile.entity.RatingTier;
import com.back.domain.rating.profile.repository.MemberRatingProfileRepository;
import com.back.domain.rating.solve.entity.FirstSolvedMode;
import com.back.domain.rating.solve.entity.MemberProblemFirstSolve;
import com.back.domain.rating.solve.repository.MemberProblemFirstSolveRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RatingProfileService {

    private static final int INITIAL_SKILL_RATING = 1000;
    private static final int MIN_SKILL_RATING = 900;
    private static final int PLACEMENT_BOOST_MATCH_COUNT = 20;
    private static final double ELO_BASE = 400.0d;
    private static final int EARLY_K = 64;
    private static final int NORMAL_K = 24;
    private static final int MAX_DELTA_PER_MATCH = 35;
    private static final double EARLY_LOSS_REDUCTION_MULTIPLIER = 0.5d;
    private static final int LOSS_STREAK_ACTIVATION = 3;
    private static final int BRONZE_SILVER_LOSS_STREAK_DELTA_FLOOR = -12;

    // Codeforces rating(800~3500+) 기준으로 first solve 보너스를 10~20 사이로 축소한다.
    private static final int FIRST_SOLVE_BASE_RATING = 800;
    private static final int FIRST_SOLVE_STEP = 150;
    private static final int FIRST_SOLVE_MIN_SCORE = 10;
    private static final int FIRST_SOLVE_MAX_SCORE = 20;

    private static final int HARD_MATCH_DIFFICULTY = 2000;
    private static final int TOP2_WINDOW_SIZE = 20;
    private static final int GOD_SEAT_LIMIT = 5;

    private final MemberRatingProfileRepository memberRatingProfileRepository;
    private final MemberProblemFirstSolveRepository memberProblemFirstSolveRepository;
    private final BattleParticipantRepository battleParticipantRepository;

    public record BattlePlacement(Member member, int rank) {}

    @Transactional
    public void ensureProfile(Member member) {
        getOrCreateProfile(member);
    }

    @Transactional
    public void applyBattlePlacements(List<BattlePlacement> placements, Integer problemDifficultyRating) {
        if (placements == null || placements.isEmpty()) {
            return;
        }
        if (!isRankedDifficulty(problemDifficultyRating)) {
            // 원본 rating이 없거나 비정상인 문제는 랭크 정산 대상에서 제외한다.
            return;
        }

        int participantCount = placements.size();
        Map<Long, MemberRatingProfile> profileMap = new HashMap<>(participantCount);
        Map<Long, Integer> skillSnapshot = new HashMap<>(participantCount);
        Map<Long, Integer> matchCountSnapshot = new HashMap<>(participantCount);

        for (BattlePlacement placement : placements) {
            Member member = placement.member();
            if (member == null || member.getId() == null) continue;

            // 같은 배틀 정산에서는 "정산 시작 시점 SR 스냅샷"으로 E를 계산해
            // 참가자 순회 순서에 따라 결과가 달라지지 않게 고정한다.
            MemberRatingProfile profile = getOrCreateProfile(member);
            profileMap.put(member.getId(), profile);
            skillSnapshot.put(member.getId(), toSkillRating(profile.getBattleRating()));
            matchCountSnapshot.put(
                    member.getId(), profile.getBattleMatchCount() == null ? 0 : profile.getBattleMatchCount());
        }

        for (BattlePlacement placement : placements) {
            Member member = placement.member();
            if (member == null || member.getId() == null) continue;

            Long memberId = member.getId();
            MemberRatingProfile profile = profileMap.get(memberId);
            if (profile == null) continue;

            int myRating = skillSnapshot.getOrDefault(memberId, INITIAL_SKILL_RATING);
            double opponentAverageRating = calculateOpponentAverageRating(memberId, skillSnapshot);
            double expectedScore = calculateExpectedScore(memberId, myRating, skillSnapshot);
            double actualScore = calculateActualScore(placement.rank(), participantCount);

            // 배치 구간(초반 20판)은 K를 크게 두어 강한 유저가 빠르게 제자리 티어로 수렴하게 한다.
            int matchCount = matchCountSnapshot.getOrDefault(memberId, 0);
            int kFactor = matchCount < PLACEMENT_BOOST_MATCH_COUNT ? EARLY_K : NORMAL_K;
            double rawDelta = kFactor * (actualScore - expectedScore);

            if (rawDelta < 0 && matchCount < PLACEMENT_BOOST_MATCH_COUNT) {
                // 초반 배치 구간에서는 패배 하락폭을 절반으로 완화해 진입 장벽을 낮춘다.
                rawDelta *= EARLY_LOSS_REDUCTION_MULTIPLIER;
            } else if (rawDelta > 0) {
                // 상승분에만 난이도/상대차 감쇠를 걸어 양학 인플레를 줄인다.
                // 하락분은 감쇠하지 않아 상위권의 패배 리스크를 유지한다.
                rawDelta *= resolveDifficultyMultiplier(problemDifficultyRating);
                rawDelta *= resolveSkillGapMultiplier(myRating - opponentAverageRating);
                rawDelta += resolveUnderdogBonus(myRating, opponentAverageRating, placement.rank());
            }

            // 1판 변동 상한으로 급격한 오버슈팅/언더슈팅을 막는다.
            int delta = clamp((int) Math.round(rawDelta), -MAX_DELTA_PER_MATCH, MAX_DELTA_PER_MATCH);
            delta = applyBronzeSilverLossStreakProtection(memberId, profile, placement.rank(), delta);
            int finalBattleDelta = applyRatingFloor(myRating, delta);
            profile.applyBattleRatingDelta(finalBattleDelta);
            profile.increaseBattleMatchCount();

            // Hard SR은 하드 문제 배틀(2000+)에서만 별도 누적한다.
            if (isHardMatch(problemDifficultyRating)) {
                int currentHardRating = toSkillRating(profile.getHardBattleRating());
                int finalHardDelta = applyRatingFloor(currentHardRating, delta);
                profile.applyHardBattleRatingDelta(finalHardDelta);
            }

            refreshTier(profile);
        }

        synchronizeGodTop5();
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
        if (!isRankedDifficulty(problem.getDifficultyRating())) {
            // rating 부재/비정상 문제는 AP/티어 보상에서 제외한다.
            return false;
        }

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
        // first-AC는 솔로/배틀 경로와 무관하게 동일한 AP 정책(10~20)을 사용한다.
        profile.applyFirstSolveScoreDelta(toFirstSolveDifficultyScore(problem.getDifficultyRating()));
        profile.increaseFirstSolvedProblemCount();

        refreshTier(profile);
        synchronizeGodTop5();
        return true;
    }

    private MemberRatingProfile getOrCreateProfile(Member member) {
        return memberRatingProfileRepository
                .findByMemberId(member.getId())
                .orElseGet(() -> memberRatingProfileRepository.save(MemberRatingProfile.createDefault(member)));
    }

    private void refreshTier(MemberRatingProfile profile) {
        Long memberId = profile.getMember().getId();
        int battleRating = toSkillRating(profile.getBattleRating());
        int hardBattleRating = toSkillRating(profile.getHardBattleRating());
        int activityPoint = profile.getFirstSolveScore() == null ? 0 : profile.getFirstSolveScore();
        long solved1400Plus =
                memberProblemFirstSolveRepository.countByMemberIdAndProblemDifficultyRatingGreaterThanEqual(
                        memberId, 1400);
        long solved1700Plus =
                memberProblemFirstSolveRepository.countByMemberIdAndProblemDifficultyRatingGreaterThanEqual(
                        memberId, 1700);
        long solved2000Plus =
                memberProblemFirstSolveRepository.countByMemberIdAndProblemDifficultyRatingGreaterThanEqual(
                        memberId, 2000);
        long solved2300Plus =
                memberProblemFirstSolveRepository.countByMemberIdAndProblemDifficultyRatingGreaterThanEqual(
                        memberId, 2300);
        // 최근 Top2 비율은 다이아/마스터 승급 게이트로 사용한다.
        double recentTop2Ratio = resolveRecentTop2Ratio(memberId);

        // 기존 정렬 호환을 위해 tierScore는 SR(battleRating)로 유지한다.
        profile.updateTierScore(battleRating);
        profile.updateTier(TierPolicy.resolveTier(
                battleRating,
                hardBattleRating,
                activityPoint,
                solved1400Plus,
                solved1700Plus,
                solved2000Plus,
                solved2300Plus,
                recentTop2Ratio));
    }

    // GOD은 MASTER_1 후보군 중 Hard SR 상위 5명에게만 부여한다.
    private void synchronizeGodTop5() {
        List<MemberRatingProfile> candidates =
                memberRatingProfileRepository.findAllByTierInOrderByHardBattleRatingDescBattleRatingDescMemberIdAsc(
                        List.of(RatingTier.GOD, RatingTier.MASTER_1));
        if (candidates == null || candidates.isEmpty()) {
            return;
        }

        // 좌석제: 상위 5명만 GOD, 나머지는 MASTER_1.
        // GOD은 컷라인 티어가 아니라 "마스터 상위권 타이틀"로 동작한다.
        int assignCount = Math.min(GOD_SEAT_LIMIT, candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            MemberRatingProfile candidate = candidates.get(i);
            if (candidate == null) continue;

            RatingTier targetTier = i < assignCount ? RatingTier.GOD : RatingTier.MASTER_1;
            if (!Objects.equals(candidate.getTier(), targetTier)) {
                candidate.updateTier(targetTier);
            }
        }
    }

    private int toFirstSolveDifficultyScore(Integer difficultyRating) {
        if (difficultyRating == null || difficultyRating <= 0) {
            return FIRST_SOLVE_MIN_SCORE;
        }

        int score =
                FIRST_SOLVE_MIN_SCORE + Math.max(0, (difficultyRating - FIRST_SOLVE_BASE_RATING) / FIRST_SOLVE_STEP);
        return clamp(score, FIRST_SOLVE_MIN_SCORE, FIRST_SOLVE_MAX_SCORE);
    }

    private int toSkillRating(Integer rawRating) {
        return rawRating == null ? INITIAL_SKILL_RATING : rawRating;
    }

    private double calculateExpectedScore(Long memberId, int myRating, Map<Long, Integer> skillSnapshot) {
        if (skillSnapshot.size() <= 1) {
            return 1.0d;
        }

        // 4인 배틀을 pairwise Elo로 근사:
        // 각 상대와의 승률을 평균내어 E(기대 성과)를 만든다.
        double sum = 0.0d;
        int opponents = 0;
        for (Map.Entry<Long, Integer> entry : skillSnapshot.entrySet()) {
            if (entry.getKey().equals(memberId)) continue;
            opponents++;
            int opponentRating = entry.getValue();
            double exponent = (opponentRating - myRating) / ELO_BASE;
            sum += 1.0d / (1.0d + Math.pow(10.0d, exponent));
        }

        if (opponents == 0) return 1.0d;
        return sum / opponents;
    }

    private double calculateActualScore(int rank, int participantCount) {
        if (participantCount <= 1) return 1.0d;
        int safeRank = clamp(rank, 1, participantCount);
        // 1등=1.0, 꼴등=0.0, 중간 등수는 선형 분배.
        return (double) (participantCount - safeRank) / (participantCount - 1);
    }

    private double calculateOpponentAverageRating(Long memberId, Map<Long, Integer> skillSnapshot) {
        long sum = 0L;
        int count = 0;
        for (Map.Entry<Long, Integer> entry : skillSnapshot.entrySet()) {
            if (entry.getKey().equals(memberId)) continue;
            sum += entry.getValue();
            count++;
        }
        if (count == 0) return toSkillRating(skillSnapshot.get(memberId));
        return (double) sum / count;
    }

    private double resolveDifficultyMultiplier(Integer difficultyRating) {
        if (difficultyRating == null) return 0.6d;
        if (difficultyRating < 1400) return 0.2d;
        if (difficultyRating < 1700) return 0.6d;
        if (difficultyRating < 2000) return 0.85d;
        return 1.0d;
    }

    private double resolveSkillGapMultiplier(double myMinusOpponentAverage) {
        if (myMinusOpponentAverage >= 700) return 0.0d;
        if (myMinusOpponentAverage >= 500) return 0.3d;
        if (myMinusOpponentAverage >= 300) return 0.6d;
        return 1.0d;
    }

    private int resolveUnderdogBonus(int myRating, double opponentAverageRating, int rank) {
        double gap = opponentAverageRating - myRating;

        // 저SR 유저가 강자 방에서 1~2등일 때 추가 가산:
        // 기본 Elo 보상만으로는 신규/저랭커 상향이 늦어지는 문제를 완화한다.
        if (rank == 1) {
            if (gap >= 700) return 8;
            if (gap >= 500) return 6;
            if (gap >= 300) return 4;
        }

        if (rank == 2) {
            if (gap >= 700) return 5;
            if (gap >= 500) return 4;
            if (gap >= 300) return 2;
        }

        return 0;
    }

    private boolean isHardMatch(Integer difficultyRating) {
        return difficultyRating != null && difficultyRating >= HARD_MATCH_DIFFICULTY;
    }

    private boolean isRankedDifficulty(Integer difficultyRating) {
        return difficultyRating != null && difficultyRating >= FIRST_SOLVE_BASE_RATING;
    }

    private double resolveRecentTop2Ratio(Long memberId) {
        List<Integer> recentRanks = battleParticipantRepository.findRecentFinalRanksByMemberId(
                memberId, PageRequest.of(0, TOP2_WINDOW_SIZE));
        if (recentRanks == null || recentRanks.isEmpty()) {
            return 0.0d;
        }

        long top2Count =
                recentRanks.stream().filter(rank -> rank != null && rank <= 2).count();
        return (double) top2Count / recentRanks.size();
    }

    private int applyRatingFloor(int currentRating, int delta) {
        int nextRating = Math.max(MIN_SKILL_RATING, currentRating + delta);
        return nextRating - currentRating;
    }

    private int applyBronzeSilverLossStreakProtection(Long memberId, MemberRatingProfile profile, int rank, int delta) {
        if (delta >= 0) {
            return delta;
        }
        if (!isBronzeOrSilver(profile.getTier())) {
            return delta;
        }
        if (rank <= 2) {
            return delta;
        }

        int previousLossStreak = resolveRecentConsecutiveLossCount(memberId);
        int currentLossStreak = previousLossStreak + 1;
        if (currentLossStreak >= LOSS_STREAK_ACTIVATION) {
            return Math.max(delta, BRONZE_SILVER_LOSS_STREAK_DELTA_FLOOR);
        }
        return delta;
    }

    private int resolveRecentConsecutiveLossCount(Long memberId) {
        List<Integer> recentRanks = battleParticipantRepository.findRecentFinalRanksByMemberId(
                memberId, PageRequest.of(0, TOP2_WINDOW_SIZE));
        if (recentRanks == null || recentRanks.isEmpty()) {
            return 0;
        }
        int streak = 0;
        for (Integer rank : recentRanks) {
            if (rank == null || rank <= 2) {
                break;
            }
            streak++;
        }
        return streak;
    }

    private boolean isBronzeOrSilver(RatingTier tier) {
        if (tier == null) {
            return true;
        }
        return tier.name().startsWith("BRONZE_") || tier.name().startsWith("SILVER_");
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
