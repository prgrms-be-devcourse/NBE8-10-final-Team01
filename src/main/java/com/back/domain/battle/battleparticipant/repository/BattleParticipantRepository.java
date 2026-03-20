package com.back.domain.battle.battleparticipant.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.back.domain.battle.battleparticipant.entity.BattleParticipant;
import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.member.member.entity.Member;

public interface BattleParticipantRepository extends JpaRepository<BattleParticipant, Long> {

    List<BattleParticipant> findByBattleRoom(BattleRoom battleRoom);

    Optional<BattleParticipant> findByBattleRoomAndMember(BattleRoom battleRoom, Member member);
}
