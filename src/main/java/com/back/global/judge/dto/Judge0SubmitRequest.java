package com.back.global.judge.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Judge0SubmitRequest(
        @JsonProperty("source_code") String sourceCode,
        @JsonProperty("language_id") int languageId,
        String stdin,
        @JsonProperty("expected_output") String expectedOutput) {}
