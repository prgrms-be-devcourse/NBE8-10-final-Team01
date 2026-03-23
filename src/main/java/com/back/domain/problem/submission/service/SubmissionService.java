package com.back.domain.problem.submission.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.back.domain.battle.battleparticipant.entity.BattleParticipant;
import com.back.domain.battle.battleparticipant.entity.BattleParticipantStatus;
import com.back.domain.battle.battleparticipant.repository.BattleParticipantRepository;
import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.battle.battleroom.entity.BattleRoomStatus;
import com.back.domain.battle.battleroom.repository.BattleRoomRepository;
import com.back.domain.battle.result.service.BattleResultService;
import com.back.domain.member.member.entity.Member;
import com.back.domain.member.member.repository.MemberRepository;
import com.back.domain.problem.submission.dto.SubmissionResponse;
import com.back.domain.problem.submission.dto.SubmitRequest;
import com.back.domain.problem.submission.entity.Submission;
import com.back.domain.problem.submission.entity.SubmissionResult;
import com.back.domain.problem.submission.repository.SubmissionRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SubmissionService {

    private final BattleRoomRepository battleRoomRepository;
    private final BattleParticipantRepository battleParticipantRepository;
    private final MemberRepository memberRepository;
    private final SubmissionRepository submissionRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final BattleResultService battleResultService;

    @Transactional
    public SubmissionResponse submit(SubmitRequest request) {

        // 1. BattleRoom 조회 + PLAYING 상태 검증 + 타이머 만료 검증
        BattleRoom room = battleRoomRepository
                .findById(request.roomId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 방입니다."));

        if (room.getStatus() != BattleRoomStatus.PLAYING) {
            throw new IllegalStateException("진행 중인 배틀이 아닙니다. 현재 상태: " + room.getStatus());
        }

        if (room.isExpired()) {
            throw new IllegalStateException("배틀 시간이 종료되었습니다.");
        }

        // 2. Member 조회
        Member member = memberRepository
                .findById(request.memberId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다."));

        // 3. BattleParticipant 조회 + 제출 가능 상태 검증
        BattleParticipant participant = battleParticipantRepository
                .findByBattleRoomAndMember(room, member)
                .orElseThrow(() -> new IllegalArgumentException("해당 방의 참여자가 아닙니다."));

        if (participant.getStatus() != BattleParticipantStatus.PLAYING) {
            throw new IllegalStateException("제출할 수 없는 상태입니다. 현재 상태: " + participant.getStatus());
        }

        // 4. Submission 생성 후 저장
        Submission submission = Submission.create(room, member, request.code(), request.language());
        submissionRepository.save(submission);

        // 5. 채점 실행 (Mock - 항상 AC 반환)
        // TODO: 실제 Judge API 연동으로 교체
        SubmissionResult result = SubmissionResult.AC;
        int totalCount = room.getProblem().getTestCases().size();
        int passedCount = totalCount;

        // 6. 채점 결과 저장
        submission.applyJudgeResult(result, passedCount, totalCount);

        // 7. WebSocket 브로드캐스트 - 제출 결과 전파
        // TODO: 리팩토링 - 트랜잭션 커밋 전 메시지 전송 문제
        //   현재 @Transactional 안에서 convertAndSend 호출 시 커밋 전에 메시지가 전송됨
        //   수신자가 DB 조회 시 아직 반영되지 않은 상태를 볼 수 있음
        //   → TransactionSynchronizationManager.registerSynchronization의 afterCommit()으로 개선 필요
        messagingTemplate.convertAndSend(
                "/topic/room/" + room.getId(),
                Map.of(
                        "type",
                        "SUBMISSION",
                        "userId",
                        member.getId(),
                        "result",
                        result.name(),
                        "passedCount",
                        passedCount,
                        "totalCount",
                        totalCount));

        // 8. AC이면 참여자 완료 처리
        if (result == SubmissionResult.AC) {
            participant.complete(LocalDateTime.now());

            // TODO: 리팩토링 - 현재 참여자 수가 최대 4명으로 고정이라 성능 문제 없으나,
            //   추후 참여자 수 확장 시 DB 집계 쿼리로 교체 필요
            //   → BattleParticipantRepository에 countByBattleRoomAndStatus 추가 후
            //      completedCount = repo.countByBattleRoomAndStatus(room, EXIT) 으로 변경
            List<BattleParticipant> allParticipants = battleParticipantRepository.findByBattleRoom(room);
            long completedCount = allParticipants.stream()
                    .filter(p -> p.getStatus() == BattleParticipantStatus.EXIT)
                    .count();

            messagingTemplate.convertAndSend(
                    "/topic/room/" + room.getId(),
                    Map.of("type", "PARTICIPANT_DONE", "userId", member.getId(), "rank", completedCount));

            // 9. 모든 참여자 완료 시 결과 정산 트리거
            boolean allFinished = allParticipants.stream().allMatch(p -> p.getStatus() == BattleParticipantStatus.EXIT);

            if (allFinished) {
                battleResultService.settle(room.getId());
            }
        }

        return SubmissionResponse.from(submission);
    }
}
