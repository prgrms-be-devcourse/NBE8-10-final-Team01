package com.back.domain.problem.submission.entity;

public enum SubmissionResult {
    AC, // Accepted
    WA, // Wrong Answer
    TLE, // Time Limit Exceeded
    MLE, // Memory Limit Exceeded
    RE, // Runtime Error
    CE, // Compile Error
    JUDGE_ERROR // Judge0 서버 오류 (타임아웃, 연결 실패 등)
}
