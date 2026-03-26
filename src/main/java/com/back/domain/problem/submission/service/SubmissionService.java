package com.back.domain.problem.submission.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.back.domain.battle.battleparticipant.entity.BattleParticipant;
import com.back.domain.battle.battleparticipant.entity.BattleParticipantStatus;
import com.back.domain.battle.battleparticipant.repository.BattleParticipantRepository;
import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.battle.battleroom.entity.BattleRoomStatus;
import com.back.domain.battle.battleroom.repository.BattleRoomRepository;
import com.back.domain.member.member.entity.Member;
import com.back.domain.member.member.repository.MemberRepository;
import com.back.domain.problem.submission.dto.SubmissionResponse;
import com.back.domain.problem.submission.dto.SubmitRequest;
import com.back.domain.problem.submission.entity.Submission;
import com.back.domain.problem.submission.repository.SubmissionRepository;
import com.back.domain.problem.testcase.entity.TestCase;
import com.back.global.judge.event.JudgeRequestedEvent;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SubmissionService {

    private final BattleRoomRepository battleRoomRepository;
    private final BattleParticipantRepository battleParticipantRepository;
    private final MemberRepository memberRepository;
    private final SubmissionRepository submissionRepository;
    private final ApplicationEventPublisher eventPublisher;

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

        // 3. BattleParticipant 조회 + 제출 가능 상태(PLAYING) 검증
        BattleParticipant participant = battleParticipantRepository
                .findByBattleRoomAndMember(room, member)
                .orElseThrow(() -> new IllegalArgumentException("해당 방의 참여자가 아닙니다."));

        if (participant.getStatus() != BattleParticipantStatus.PLAYING) {
            throw new IllegalStateException("제출할 수 없는 상태입니다. 현재 상태: " + participant.getStatus());
        }

        // 4. Submission 생성 후 저장 (result = null, 채점 중 상태)
        Submission submission = Submission.create(room, member, request.code(), request.language());
        submissionRepository.save(submission);

        // 5. 테스트케이스 로드 후 이벤트 발행 → 트랜잭션 커밋 후 비동기 채점 시작 → 즉시 JUDGING 응답
        // new ArrayList<>()로 강제 초기화: PersistentBag을 트랜잭션 안에서 일반 List로 변환,
        // 트랜잭션 종료 후 세션이 닫혀도 JudgeService에서 안전하게 접근 가능
        List<TestCase> testCases = new ArrayList<>(room.getProblem().getTestCases());
        // 이벤트 기반으로 변경: 이벤트를 발행하고 끝, JudgeService에서 수신해서 judge실행
        eventPublisher.publishEvent(new JudgeRequestedEvent(
                submission.getId(), room.getId(), member.getId(), request.code(), request.language(), testCases));

        return new SubmissionResponse(submission.getId(), "JUDGING", 0, 0);
    }
}
