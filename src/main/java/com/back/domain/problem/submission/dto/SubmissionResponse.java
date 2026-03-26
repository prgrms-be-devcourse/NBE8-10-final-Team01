package com.back.domain.problem.submission.dto;

import com.back.domain.problem.submission.entity.Submission;

public record SubmissionResponse(Long submissionId, String result, int passedCount, int totalCount) {

    public static SubmissionResponse from(Submission submission) {
        return new SubmissionResponse(
                submission.getId(),
                submission.getResult() != null ? submission.getResult().name() : "JUDGING",
                submission.getPassedCount() != null ? submission.getPassedCount() : 0,
                submission.getTotalCount() != null ? submission.getTotalCount() : 0);
    }
}
