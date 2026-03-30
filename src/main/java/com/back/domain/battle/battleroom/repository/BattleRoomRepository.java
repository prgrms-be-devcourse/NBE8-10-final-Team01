package com.back.domain.battle.battleroom.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.battle.battleroom.entity.BattleRoomStatus;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface BattleRoomRepository extends JpaRepository<BattleRoom, Long> {

    // 타이머 만료된 진행중 방 조회 (스케줄러용)
    List<BattleRoom> findByStatusAndTimerEndBefore(BattleRoomStatus status, LocalDateTime now);

    // 특정 상태의 방 목록 조회 (관전용)
    List<BattleRoom> findByStatus(BattleRoomStatus status);

    @Query("SELECT r FROM BattleRoom r JOIN FETCH r.problem WHERE r.id = :id")
    Optional<BattleRoom> findByIdWithProblem(@Param("id") Long id);

    // joinRoom 동시 요청 직렬화용 비관적 락 조회
    // 타임아웃 1000ms: 정상 joinRoom 트랜잭션은 수백ms 내 완료되므로 충분한 여유.
    // 배포 후 실제 응답시간 측정 후 조정 권장.
    // PostgreSQL에서 Hibernate가 SET LOCAL lock_timeout = 1000 으로 변환하여 처리.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "1000"))
    @Query("SELECT r FROM BattleRoom r WHERE r.id = :id")
    Optional<BattleRoom> findByIdWithLock(@Param("id") Long id);
}
