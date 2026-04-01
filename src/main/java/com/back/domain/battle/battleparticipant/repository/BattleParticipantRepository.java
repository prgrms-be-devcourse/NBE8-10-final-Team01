package com.back.domain.battle.battleparticipant.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.back.domain.battle.battleparticipant.entity.BattleParticipant;
import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.battle.battleroom.entity.BattleRoomStatus;
import com.back.domain.member.member.entity.Member;

public interface BattleParticipantRepository extends JpaRepository<BattleParticipant, Long> {

    List<BattleParticipant> findByBattleRoom(BattleRoom battleRoom);

    Optional<BattleParticipant> findByBattleRoomAndMember(BattleRoom battleRoom, Member member);

    // 방별 참여자 수 조회 (N+1 방지용 - 전체 로드 없이 COUNT만 조회)
    long countByBattleRoom(BattleRoom battleRoom);

    /**
     * 특정 회원의 종료된 배틀 전적을 페이지 단위로 조회한다.
     *
     * join fetch 를 사용한 이유:
     * - battleRoom, problem 제목을 응답에 바로 써야 하므로
     * - 서비스/DTO 변환 시 N+1 문제를 줄이기 위해 같이 읽는다.
     *
     * 정렬 기준:
     * - 최신 배틀이 먼저 오도록 battleRoom.createdAt 내림차순
     */
    @Query(value = """
                    select bp
                    from BattleParticipant bp
                    join fetch bp.battleRoom br
                    join fetch br.problem p
                    where bp.member.id = :memberId
                      and br.status = :status
                    order by br.createdAt desc
                    """, countQuery = """
                    select count(bp)
                    from BattleParticipant bp
                    join bp.battleRoom br
                    where bp.member.id = :memberId
                      and br.status = :status
                    """)
    Page<BattleParticipant> findFinishedBattleResultsByMemberId(
            @Param("memberId") Long memberId, @Param("status") BattleRoomStatus status, Pageable pageable);

    /**
     * 현재 배틀 중인 방에서 PLAYING 상태인 특정 유저의 참여자 정보를 조회
     * 유저도 PLAYING 상태,
     * 방도 PLAYING 상태
     * - p.status = PLAYING — 이미 EXIT이거나 ABANDONED인 유저는 이탈 처리 안 함
     * - r.status = PLAYING — 이미 끝난 방(FINISHED)의 참여자는 건드리지 않음
     * join fetch p.battleRoom 을 쓰는 이유는
     * 핸들러에서 participant.getBattleRoom().getId()를 호출할 때 N+1 문제 방지
     *
     * DB의 partial unique index(uq_one_playing_per_member)가 동시에 2개 이상의 PLAYING 참여자를
     * 방지하므로 결과는 항상 0개 또는 1개. 2개 이상이면 IncorrectResultSizeDataAccessException 발생.
     */
    @Query("""
            select p from BattleParticipant p
            join fetch p.battleRoom r
            where p.member.id = :memberId
              and p.status = com.back.domain.battle.battleparticipant.entity.BattleParticipantStatus.PLAYING
              and r.status = com.back.domain.battle.battleroom.entity.BattleRoomStatus.PLAYING
            """)
    Optional<BattleParticipant> findPlayingParticipantByMemberId(@Param("memberId") Long memberId);
}
