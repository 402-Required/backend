package com.example.loopcut.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final String BASE = "/api/v1";

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    /** 이 프로젝트의 @RestController 전체에 /api/v1 접두사를 붙인다 (swagger 등 외부 컨트롤러는 제외). */
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        // HandlerTypePredicate는 조건을 OR로 묶으므로 직접 AND 조건을 만든다.
        configurer.addPathPrefix(BASE, type ->
                type.getPackageName().startsWith("com.example.loopcut")
                        && type.isAnnotationPresent(RestController.class));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns(BASE + "/**")
                .excludePathPatterns(
                        BASE + "/auth/signup",
                        BASE + "/auth/login",
                        BASE + "/auth/google",
                        BASE + "/auth/refresh",
                        BASE + "/categories",
                        BASE + "/services",
                        BASE + "/services/**",
                        BASE + "/faqs");
    }
}
