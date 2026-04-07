package com.back.domain.battle.battleroom.entity;

import java.time.Duration;
import java.time.LocalDateTime;

import com.back.domain.problem.problem.entity.Problem;
import com.back.global.jpa.entity.BaseEntity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "battle_rooms")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BattleRoom extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "battle_room_seq_gen")
    @SequenceGenerator(name = "battle_room_seq_gen", sequenceName = "battle_room_id_seq", allocationSize = 50)
    private Long id;

    /**
     * 낙관적 락 — settle() 동시 진입 시 하나만 커밋 성공하도록 보장.
     * idempotent 체크(if FINISHED return)가 대부분의 중복 호출을 차단하고,
     * 낙관적 락은 두 트랜잭션이 동시에 체크를 통과한 극히 드문 race condition을 DB 레벨에서 차단한다.
     */
    @Version
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "problem_id")
    private Problem problem;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BattleRoomStatus status;

    private Integer maxPlayers;
    private LocalDateTime timerEnd;
    private LocalDateTime startedAt;

    /**
     * 방은 waiting 상태로 먼저 생성된다. (아무도 입장 안한 상태)
     * 참여자는 READY 상태 (입장 대기 중)
     * timerEnd는 null (모두 입장 후 세팅됨)
     */
    public static BattleRoom create(Problem problem, int maxPlayers) {
        BattleRoom room = new BattleRoom();
        room.problem = problem;
        room.maxPlayers = maxPlayers;
        room.status = BattleRoomStatus.WAITING;

        return room;
    }

    public void startBattle(Duration duration) {
        this.status = BattleRoomStatus.PLAYING;
        this.startedAt = LocalDateTime.now();
        this.timerEnd = this.startedAt.plus(duration);
    }

    public void finish() {
        this.status = BattleRoomStatus.FINISHED;
    }

    public boolean isExpired() {
        return timerEnd != null && LocalDateTime.now().isAfter(timerEnd);
    }
}
