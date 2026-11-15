package com.example.loopcut.common;

import com.example.loopcut.auth.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Authorization: Bearer 토큰을 검증하고 로그인 사용자 ID를 request attribute로 넘긴다. */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String USER_ID = "userId";

    private final JwtTokenProvider jwtTokenProvider;

    public AuthInterceptor(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equals(request.getMethod())) {
            return true;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw BusinessException.unauthorized("UNAUTHORIZED", "로그인이 필요해요.");
        }
        long userId = jwtTokenProvider.parseUserId(header.substring(7).trim());
        request.setAttribute(USER_ID, userId);
        return true;
    }
}
