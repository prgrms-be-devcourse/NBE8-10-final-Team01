package com.back.global.rsData;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record RsData<T>(String resultCode, @JsonIgnore int statusCode, String msg, T data) {
    // 데이터 있을때
    public static <T> RsData<T> of(String resultCode, String msg, T data) {
        int status = parseStatus(resultCode);
        return new RsData<>(resultCode, status, msg, data);
    }

    private static int parseStatus(String resultCode) {
        for (String part : resultCode.split("[-_]")) {
            try {
                int n = Integer.parseInt(part);
                if (n >= 100 && n < 600) return n;
            } catch (NumberFormatException ignored) {
            }
        }
        return 500;
    }

    // 데이터 없을때
    public static <T> RsData<T> of(String resultCode, String msg) {
        return of(resultCode, msg, null);
    }
}
