package com.back.domain.member.member.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.back.domain.battle.battleparticipant.entity.BattleParticipant;
import com.back.domain.battle.battleparticipant.entity.BattleParticipantStatus;
import com.back.domain.battle.battleparticipant.repository.BattleParticipantRepository;
import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.battle.battleroom.repository.BattleRoomRepository;
import com.back.domain.member.member.entity.Member;
import com.back.domain.member.member.repository.MemberRepository;
import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.problem.problem.enums.DifficultyLevel;
import com.back.domain.problem.problem.enums.InputMode;
import com.back.domain.problem.problem.enums.JudgeType;
import com.back.domain.problem.problem.repository.ProblemRepository;
import com.back.domain.problem.solo.submission.entity.SoloSubmission;
import com.back.domain.problem.solo.submission.repository.SoloSubmissionRepository;
import com.back.domain.problem.submission.entity.SubmissionResult;
import com.back.global.IntegrationTestBase;

@Transactional
class MemberSolveHeatmapRepositoryIntegrationTest extends IntegrationTestBase {

    @Autowired
    private SoloSubmissionRepository soloSubmissionRepository;

    @Autowired
    private BattleParticipantRepository battleParticipantRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private ProblemRepository problemRepository;

    @Autowired
    private BattleRoomRepository battleRoomRepository;

    @Test
    @DisplayName("Solo heatmap source keeps only one first AC per problem even after replaying the same problem.")
    void soloQuery_returnsOneFirstAcPerProblem() {
        Member member = memberRepository.saveAndFlush(Member.createUser("solo-user", "solo@example.com", "pw"));
        Problem problem = saveProblem("solo-problem");

        SoloSubmission first = SoloSubmission.create(member, problem, "code-1", "java");
        first.applyJudgeResult(SubmissionResult.AC, 1, 1);
        soloSubmissionRepository.saveAndFlush(first);

        SoloSubmission second = SoloSubmission.create(member, problem, "code-2", "java");
        second.applyJudgeResult(SubmissionResult.AC, 1, 1);
        soloSubmissionRepository.saveAndFlush(second);

        List<LocalDateTime> firstAcTimes =
                soloSubmissionRepository.findFirstAcTimesByMemberIdAndResult(member.getId(), SubmissionResult.AC);

        assertThat(firstAcTimes).hasSize(1);
        assertThat(firstAcTimes.get(0)).isNotNull();
    }

    @Test
    @DisplayName("Battle heatmap source keeps only one first solve per problem even across multiple rooms.")
    void battleQuery_returnsOneFirstSolvePerProblem() {
        Member member = memberRepository.saveAndFlush(Member.createUser("battle-user", "battle@example.com", "pw"));
        Problem problem = saveProblem("battle-problem");

        BattleRoom room1 = battleRoomRepository.saveAndFlush(BattleRoom.create(problem, 4));
        room1.startBattle(Duration.ofMinutes(10));
        battleRoomRepository.saveAndFlush(room1);

        BattleParticipant participant1 = BattleParticipant.create(room1, member);
        participant1.join();
        participant1.complete(LocalDateTime.now().minusDays(1));
        battleParticipantRepository.saveAndFlush(participant1);

        BattleRoom room2 = battleRoomRepository.saveAndFlush(BattleRoom.create(problem, 4));
        room2.startBattle(Duration.ofMinutes(10));
        battleRoomRepository.saveAndFlush(room2);

        BattleParticipant participant2 = BattleParticipant.create(room2, member);
        participant2.join();
        participant2.complete(LocalDateTime.now());
        battleParticipantRepository.saveAndFlush(participant2);

        List<LocalDateTime> firstSolvedTimes = battleParticipantRepository.findFirstSolvedAtByMemberIdAndStatus(
                member.getId(), BattleParticipantStatus.SOLVED);

        assertThat(firstSolvedTimes).hasSize(1);
        assertThat(firstSolvedTimes.get(0)).isNotNull();
    }

    private Problem saveProblem(String sourceProblemId) {
        Problem problem = instantiateProblem();
        ReflectionTestUtils.setField(problem, "sourceProblemId", sourceProblemId);
        ReflectionTestUtils.setField(problem, "title", "Problem " + sourceProblemId);
        ReflectionTestUtils.setField(problem, "difficulty", DifficultyLevel.EASY);
        ReflectionTestUtils.setField(problem, "content", "content");
        ReflectionTestUtils.setField(problem, "difficultyRating", 1200);
        ReflectionTestUtils.setField(problem, "timeLimitMs", 1000L);
        ReflectionTestUtils.setField(problem, "memoryLimitMb", 256L);
        ReflectionTestUtils.setField(problem, "inputFormat", "input");
        ReflectionTestUtils.setField(problem, "outputFormat", "output");
        ReflectionTestUtils.setField(problem, "inputMode", InputMode.STDIO);
        ReflectionTestUtils.setField(problem, "judgeType", JudgeType.EXACT);
        ReflectionTestUtils.setField(problem, "checkerCode", null);
        return problemRepository.saveAndFlush(problem);
    }

    private Problem instantiateProblem() {
        try {
            var constructor = Problem.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to instantiate problem for test setup.", e);
        }
    }
}
