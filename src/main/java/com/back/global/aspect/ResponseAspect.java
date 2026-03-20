package com.back.global.aspect;

import com.back.global.rsData.RsData;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
@RequiredArgsConstructor
public class ResponseAspect {

    private final HttpServletResponse response;

    @Around(
            "execution(public com.back.global.rsData.RsData *(..)) && " +
                    "(within(@org.springframework.stereotype.Controller *) || within(@org.springframework.web.bind.annotation.RestController *)) && " +
                    "(@annotation(org.springframework.web.bind.annotation.GetMapping) || " +
                    "@annotation(org.springframework.web.bind.annotation.PostMapping) || " +
                    "@annotation(org.springframework.web.bind.annotation.PutMapping) || " +
                    "@annotation(org.springframework.web.bind.annotation.DeleteMapping) || " +
                    "@annotation(org.springframework.web.bind.annotation.RequestMapping))"
    )
    public Object handleResponse(ProceedingJoinPoint pjp) throws Throwable {
        // 1. 실제 컨트롤러 메서드 실행
        Object result = pjp.proceed();

        // 2. 결과가 RsData 타입이라면 내부 statusCode를 응답 헤더에 세팅
        if (result instanceof RsData<?> rsData) {
            response.setStatus(rsData.statusCode());
        }

        return result;
    }
}