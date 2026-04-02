package com.back.domain.matching.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.back.domain.matching.queue.dto.MatchStateV2Response;
import com.back.domain.matching.queue.dto.MatchStatus;
import com.back.domain.matching.queue.dto.MatchingEventResponse;
import com.back.domain.matching.queue.dto.MatchingEventType;
import com.back.domain.matching.queue.model.Difficulty;
import com.back.domain.matching.queue.model.QueueKey;

class MatchingEventPublisherTest {

    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final MatchingEventPublisher matchingEventPublisher = new MatchingEventPublisher(messagingTemplate);

    @Test
    @DisplayName("queue 상태 변화는 category/difficulty 기준 topic 으로 발행된다")
    void publishQueueStateChanged_usesQueueTopic() {
        QueueKey queueKey = new QueueKey("Array", Difficulty.EASY);
        ArgumentCaptor<String> destinationCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MatchingEventResponse> eventCaptor = ArgumentCaptor.forClass(MatchingEventResponse.class);

        matchingEventPublisher.publishQueueStateChanged(queueKey, 3);

        verify(messagingTemplate).convertAndSend(destinationCaptor.capture(), eventCaptor.capture());

        assertThat(destinationCaptor.getValue()).isEqualTo("/topic/matching/queue/ARRAY/EASY");
        assertThat(eventCaptor.getValue().type()).isEqualTo(MatchingEventType.QUEUE_STATE_CHANGED);
        assertThat(eventCaptor.getValue().queue()).isNotNull();
        assertThat(eventCaptor.getValue().queue().waitingCount()).isEqualTo(3);
        assertThat(eventCaptor.getValue().queue().requiredCount()).isEqualTo(4);
        assertThat(eventCaptor.getValue().match()).isNull();
    }

    @Test
    @DisplayName("ready-check 시작 이벤트는 사용자별 matching 채널로 발행된다")
    void publishReadyCheckStarted_usesUserDestination() {
        MatchStateV2Response matchState = new MatchStateV2Response(MatchStatus.ACCEPT_PENDING, null, null, null);
        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> destinationCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MatchingEventResponse> eventCaptor = ArgumentCaptor.forClass(MatchingEventResponse.class);

        matchingEventPublisher.publishReadyCheckStarted(1L, matchState);

        verify(messagingTemplate)
                .convertAndSendToUser(userCaptor.capture(), destinationCaptor.capture(), eventCaptor.capture());

        assertThat(userCaptor.getValue()).isEqualTo("1");
        assertThat(destinationCaptor.getValue()).isEqualTo("/queue/matching");
        assertThat(eventCaptor.getValue().type()).isEqualTo(MatchingEventType.READY_CHECK_STARTED);
        assertThat(eventCaptor.getValue().queue()).isNull();
        assertThat(eventCaptor.getValue().match()).isEqualTo(matchState);
    }
}
