package com.back.global.exception;

import lombok.Getter;

@Getter
public class ServiceException extends RuntimeException {
    private final String resultCode;
    private final String msg;

    public ServiceException(String resultCode, String msg) {
        super(resultCode + " : " + msg);
        this.resultCode = resultCode;
        this.msg = msg;
    }

    public ServiceException(String resultCode, String msg, Throwable cause) {
        super(resultCode + " : " + msg, cause);
        this.resultCode = resultCode;
        this.msg = msg;
    }

    public String getLocation() {
        StackTraceElement[] stackTrace = this.getStackTrace();
        if (stackTrace != null && stackTrace.length > 0) {
            StackTraceElement top = stackTrace[0];
            return String.format("%s.%s:%d", top.getClassName(), top.getMethodName(), top.getLineNumber());
        }
        return "Unknown Location";
    }
}
