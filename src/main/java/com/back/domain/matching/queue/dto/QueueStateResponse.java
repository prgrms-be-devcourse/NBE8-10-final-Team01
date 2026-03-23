package com.back.domain.matching.queue.dto;

public record QueueStateResponse(boolean inQueue, String category, String difficulty, int waitingCount) {}
