package com.back.domain.battle.result.dto;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;

import com.back.domain.battle.battleparticipant.entity.BattleParticipant;
import com.back.domain.battle.battleparticipant.entity.BattleParticipantStatus;

/**
 * 내 전적 조회 응답 DTO
 *
 * battleResults: 전적 목록
 * pageInfo: 페이징 정보
 */
public record MyBattleResultsResponse(List<MyBattleResultItem> battleResults, PageInfo pageInfo) {

    /**
     * 전적 목록의 한 줄(row)에 해당하는 데이터
     */
    public record MyBattleResultItem(
            Long roomId,
            Long problemId,
            String problemTitle,
            Integer finalRank,
            Long scoreDelta,
            boolean solved,
            LocalDateTime finishTime,
            LocalDateTime playedAt) {

        /**
         * BattleParticipant 엔티티를 전적 응답 아이템으로 변환
         *
         * solved:
         * - 배틀에서 정답 처리 후 complete() 되면 EXIT 상태가 되므로
         * - EXIT 이면 solved = true 로 본다.
         */
        public static MyBattleResultItem from(BattleParticipant participant) {
            return new MyBattleResultItem(
                    participant.getBattleRoom().getId(),
                    participant.getBattleRoom().getProblem().getId(),
                    participant.getBattleRoom().getProblem().getTitle(),
                    participant.getFinalRank(),
                    participant.getScoreDelta(),
                    participant.getStatus() == BattleParticipantStatus.EXIT,
                    participant.getFinishTime(),
                    // 배틀이 생성된 시각을 "플레이한 시각" 기준값으로 사용
                    participant.getBattleRoom().getCreatedAt());
        }
    }

    /**
     * 페이지 정보 DTO
     */
    public record PageInfo(int page, int size, long totalElements, int totalPages, boolean hasNext) {
        public static PageInfo from(Page<?> page) {
            return new PageInfo(
                    page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages(), page.hasNext());
        }
    }

    /**
     * Page<BattleParticipant> -> MyBattleResultsResponse 변환
     */
    public static MyBattleResultsResponse from(Page<BattleParticipant> participantPage) {
        List<MyBattleResultItem> items = participantPage.getContent().stream()
                .map(MyBattleResultItem::from)
                .toList();

        return new MyBattleResultsResponse(items, PageInfo.from(participantPage));
    }
}
