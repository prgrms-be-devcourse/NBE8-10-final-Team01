package com.back.domain.battle.battleparticipant.entity;

import java.time.LocalDateTime;

import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.member.member.entity.Member;
import com.back.global.jpa.entity.BaseEntity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "battle_participants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BattleParticipant extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "participant_seq_gen")
    @SequenceGenerator(name = "participant_seq_gen", sequenceName = "participant_id_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private BattleRoom battleRoom;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private Member member;

    @Enumerated(EnumType.STRING)
    private BattleParticipantStatus status;

    private Integer finalRank;
    private Long scoreDelta; // 이 판으로 변동된 점수
    private LocalDateTime finishTime; // 문제를 다 푼 시각

    public static BattleParticipant create(BattleRoom battleRoom, Member member) {
        BattleParticipant participant = new BattleParticipant();
        participant.battleRoom = battleRoom;
        participant.member = member;
        participant.status = BattleParticipantStatus.READY;
        participant.scoreDelta = 0L;
        return participant;
    }

    public void join() {
        this.status = BattleParticipantStatus.PLAYING;
    }

    public void complete(LocalDateTime finishTime) {
        this.status = BattleParticipantStatus.EXIT;
        this.finishTime = finishTime;
    }

    public void applyResult(int rank, long delta) {
        this.finalRank = rank;
        this.scoreDelta = delta;
    }
}
