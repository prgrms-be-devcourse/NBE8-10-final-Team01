package com.back.domain.matching.queue.dto;

/**
 * v2 queue/me 응답 DTO
 *
 * 이 DTO는 SEARCHING 화면만 위해 존재한다.
 * 즉 "지금 몇 명이 모였는가"를 프론트가 그릴 수 있게 해주고,
 * ready-check나 room 준비 상태는 담지 않는다.
 */
public record QueueStateV2Response(
        boolean inQueue, String category, String difficulty, int waitingCount, int requiredCount) {}
