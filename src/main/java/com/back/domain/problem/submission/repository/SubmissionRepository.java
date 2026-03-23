package com.back.domain.problem.submission.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.member.member.entity.Member;
import com.back.domain.problem.submission.entity.Submission;
import com.back.domain.problem.submission.entity.SubmissionResult;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    List<Submission> findByBattleRoom(BattleRoom battleRoom);

    List<Submission> findByBattleRoomAndMember(BattleRoom battleRoom, Member member);

    // AC 제출 중 가장 먼저 제출된 것 조회 (결과 조회용)
    // Judge0 연동 후 여러 번 제출 가능해지면 이 쿼리로 첫 AC를 찾음
    Optional<Submission> findFirstByBattleRoomAndMemberAndResultOrderByCreatedAtAsc(
            BattleRoom room, Member member, SubmissionResult result);

    // AC 제출 시각 이전의 WA 제출 횟수 (패널티 계산용)
    @Query("SELECT COUNT(s) FROM Submission s " + "WHERE s.battleRoom = :room "
            + "AND s.member = :member "
            + "AND s.result = :result "
            + "AND s.createdAt < :finishTime")
    long countByBattleRoomAndMemberAndResultAndCreatedAtBefore(
            @Param("room") BattleRoom room,
            @Param("member") Member member,
            @Param("result") SubmissionResult result,
            @Param("finishTime") LocalDateTime finishTime);
}
