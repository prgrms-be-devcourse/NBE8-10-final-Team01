package com.back.global.globalExceptionHandler;

import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.back.global.exception.ServiceException;
import com.back.global.rsData.RsData;

import jakarta.validation.ConstraintViolationException;

@ControllerAdvice
public class GlobalExceptionHandler {

    // 1. 데이터가 없을 때 (404)
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<RsData<Void>> handle(NoSuchElementException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(RsData.of("404-1", "해당 데이터가 존재하지 않습니다."));
    }

    // 2. 제약 조건 위반 (주로 파라미터 유효성 검사 실패)
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<RsData<Void>> handle(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(violation -> {
                    String path = violation.getPropertyPath().toString();
                    String field = path.contains(".") ? path.substring(path.lastIndexOf(".") + 1) : path;

                    String template = violation.getMessageTemplate();
                    String[] bits = template.split("\\.");
                    String code = (bits.length >= 2) ? bits[bits.length - 2] : "Unknown";

                    return String.format("%s-%s-%s", field, code, violation.getMessage());
                })
                .sorted()
                .collect(Collectors.joining("\n"));

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(RsData.of("400-1", message));
    }

    // 3. @Valid 검증 실패 (DTO 유효성 검사 실패)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<RsData<Void>> handle(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getAllErrors().stream()
                .filter(FieldError.class::isInstance)
                .map(FieldError.class::cast)
                .map(err -> String.format("%s-%s-%s", err.getField(), err.getCode(), err.getDefaultMessage()))
                .sorted()
                .collect(Collectors.joining("\n"));

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(RsData.of("400-1", message));
    }

    // 4. JSON 파싱 에러 (잘못된 형식의 JSON 요청)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<RsData<Void>> handle(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(RsData.of("400-1", "요청 본문이 올바르지 않습니다."));
    }

    // 5. 필수 헤더 누락
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<RsData<Void>> handle(MissingRequestHeaderException ex) {
        String message = String.format("%s-%s-%s", ex.getHeaderName(), "NotBlank", ex.getLocalizedMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(RsData.of("400-1", message));
    }

    // 6. 우리가 직접 만든 커스텀 예외 (ServiceException)
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<RsData<Void>> handle(ServiceException ex) {
        RsData<Void> rsData = ex.getRsData();
        return ResponseEntity.status(rsData.statusCode()).body(rsData);
    }
}
