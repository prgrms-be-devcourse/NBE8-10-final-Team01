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

    String toQueueTopic(QueueKey queueKey) {
        return "/topic/matching/queue/" + queueKey.category() + "/"
                + queueKey.difficulty().name();
    }
}
