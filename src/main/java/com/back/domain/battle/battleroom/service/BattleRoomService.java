package com.back.domain.battle.battleroom.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.back.domain.battle.battleparticipant.entity.BattleParticipant;
import com.back.domain.battle.battleparticipant.repository.BattleParticipantRepository;
import com.back.domain.battle.battleroom.dto.CreateRoomRequest;
import com.back.domain.battle.battleroom.dto.CreateRoomResponse;
import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.battle.battleroom.repository.BattleRoomRepository;
import com.back.domain.member.member.entity.Member;
import com.back.domain.member.member.repository.MemberRepository;
import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.problem.problem.repository.ProblemRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BattleRoomService {

    private final BattleRoomRepository battleRoomRepository;
    private final BattleParticipantRepository battleParticipantRepository;
    private final ProblemRepository problemRepository;
    private final MemberRepository memberRepository;

    @Transactional
    public CreateRoomResponse createRoom(CreateRoomRequest request) {

        // 1. problemId=1 로 문제 조회, 없으면 예외
        Problem problem = problemRepository
                .findById(request.problemId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 문제입니다."));

        // 2. BattleRoom 생성 후 저장
        BattleRoom room = BattleRoom.create(problem, request.maxPlayers());
        battleRoomRepository.save(room);

        // 3. 참여자 목록 조회
        List<Member> members = memberRepository.findAllById(request.participantIds());
        if (members.size() != request.participantIds().size()) {
            throw new IllegalArgumentException("존재하지 않는 참여자가 포함되어 있습니다.");
        }

        // 4. BattleParticipant 4개 생성 후 저장 (4명으로 고정이니까)
        // TODO: battleParticipantRepository::save를 효율적으로 바꿔야할지도 모름
        members.stream()
                .map(member -> BattleParticipant.create(room, member))
                .forEach(battleParticipantRepository::save);

        return CreateRoomResponse.from(room);
    }
}
