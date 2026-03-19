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

    private String status; // READY, PLAYING, EXIT
    private Integer finalRank;
    private Long scoreDelta; // 이 판으로 변동된 점수

    private LocalDateTime finishTime; // 문제를 다 푼 시각
}
