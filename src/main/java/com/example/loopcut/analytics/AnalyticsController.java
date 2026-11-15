package com.example.loopcut.analytics;

import com.example.loopcut.analytics.AnalyticsService.Summary;
import com.example.loopcut.common.ApiResponse;
import com.example.loopcut.common.AuthInterceptor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/analytics")
public class AnalyticsController {

    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ApiResponse<Summary> summary(@RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                        @RequestParam(required = false) String month) {
        return ApiResponse.of(service.summary(userId, month));
    }
}
