package com.back.global.judge.dto;

public record Judge0SubmitResponse(
        String token,
        Status status,
        String stdout,
        String stderr,

        @com.fasterxml.jackson.annotation.JsonProperty("compile_output")
        String compileOutput,

        String message) {

    public record Status(int id, String description) {}

    public boolean isCompleted() {
        return status != null && status.id() >= 3;
    }
}
