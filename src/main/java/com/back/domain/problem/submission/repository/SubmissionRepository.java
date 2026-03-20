package com.back.domain.problem.submission.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.member.member.entity.Member;
import com.back.domain.problem.submission.entity.Submission;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    List<Submission> findByBattleRoom(BattleRoom battleRoom);

    List<Submission> findByBattleRoomAndMember(BattleRoom battleRoom, Member member);
}
