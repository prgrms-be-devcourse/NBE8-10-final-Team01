package com.back.domain.matching.queue.service;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.back.domain.matching.queue.dto.MatchStateV2Response;
import com.back.domain.matching.queue.dto.MatchingEventResponse;
import com.back.domain.matching.queue.dto.MatchingEventType;
import com.back.domain.matching.queue.dto.QueueStateV2Response;
import com.back.domain.matching.queue.model.QueueKey;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchingEventPublisher {

    private static final int REQUIRED_MATCH_SIZE = 4;
    private static final String USER_MATCHING_DESTINATION = "/queue/matching";

    private final SimpMessagingTemplate messagingTemplate;

    public void publishQueueStateChanged(QueueKey queueKey, int waitingCount) {
        // queue topic 은 "같은 queueKey 대기자 공용" 채널이므로 inQueue=true snapshot 으로 통일한다.
        // 예: ARRAY/EASY 대기자가 3명 남아 있으면 모두 같은 waitingCount=3 이벤트를 받는다.
        QueueStateV2Response queueState = new QueueStateV2Response(
                true, queueKey.category(), queueKey.difficulty().name(), waitingCount, REQUIRED_MATCH_SIZE);

        messagingTemplate.convertAndSend(
                toQueueTopic(queueKey),
                new MatchingEventResponse(MatchingEventType.QUEUE_STATE_CHANGED, queueState, null));
    }

    public void publishReadyCheckStarted(Long userId, MatchStateV2Response matchState) {
        log.info("READY_CHECK_STARTED 발행 - userId={}, destination={}", userId, USER_MATCHING_DESTINATION);
        messagingTemplate.convertAndSendToUser(
                String.valueOf(userId),
                USER_MATCHING_DESTINATION,
                new MatchingEventResponse(MatchingEventType.READY_CHECK_STARTED, null, matchState));
    }

    // ready-check 상세 상태 변화는 개인 채널로만 전달한다.
    public void publishReadyDecisionChanged(Long userId, MatchStateV2Response matchState) {
        publishUserMatchEvent(userId, MatchingEventType.READY_DECISION_CHANGED, matchState);
    }

    // 거절 또는 room 생성 실패로 종료된 경우 취소 이벤트를 보낸다.
    public void publishMatchCancelled(Long userId, MatchStateV2Response matchState) {
        publishUserMatchEvent(userId, MatchingEventType.MATCH_CANCELLED, matchState);
    }

    // ready-check 만료는 스케줄러가 감지한 뒤 개인 채널로 전달한다.
    public void publishMatchExpired(Long userId, MatchStateV2Response matchState) {
        publishUserMatchEvent(userId, MatchingEventType.MATCH_EXPIRED, matchState);
    }

    // 방 준비 완료는 battle room 진입 전까지 개인 채널로 전달한다.
    public void publishRoomReady(Long userId, MatchStateV2Response matchState) {
        publishUserMatchEvent(userId, MatchingEventType.ROOM_READY, matchState);
    }

    String toQueueTopic(QueueKey queueKey) {
        return "/topic/matching/queue/" + queueKey.category() + "/"
                + queueKey.difficulty().name();
    }

    private void publishUserMatchEvent(Long userId, MatchingEventType type, MatchStateV2Response matchState) {
        log.info("{} 발행 - userId={}, destination={}", type, userId, USER_MATCHING_DESTINATION);
        messagingTemplate.convertAndSendToUser(
                String.valueOf(userId), USER_MATCHING_DESTINATION, new MatchingEventResponse(type, null, matchState));
    }
}
