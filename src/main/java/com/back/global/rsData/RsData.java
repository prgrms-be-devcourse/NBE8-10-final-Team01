package com.back.global.rsData;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record RsData<T>(String resultCode, @JsonIgnore int statusCode, String msg, T data) {
    // 데이터 있을때
    public static <T> RsData<T> of(String resultCode, String msg, T data) {
        int status = Integer.parseInt(resultCode.split("-")[0]);
        return new RsData<>(resultCode, status, msg, data);
    }

    // 데이터 없을때
    public static <T> RsData<T> of(String resultCode, String msg) {
        return of(resultCode, msg, null);
    }
}
