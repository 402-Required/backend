package com.example.loopcut.analytics;

import com.example.loopcut.analytics.AnalyticsService.Summary;
import com.example.loopcut.common.ApiResponse;
import com.example.loopcut.common.AuthInterceptor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "분석", description = "월별 지출 분석")
@RestController
@RequestMapping("/analytics")
public class AnalyticsController {

    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @Operation(summary = "월별 지출 분석",
            description = """
                    **화면: 분석 (월 합계, 전월 대비, 최근 4개월 추이, 카테고리별 도넛 차트)**

                    - `total`: 지난 달은 그 달 결제 합계, **이번 달은 홈의 `expectedTotal`과 같은 값**(이미 결제된 금액 + 남은 결제 예정)
                    - `changeRate`(%)와 `changeAmount`(원): 전월 대비. 전월 지출이 0이면 `changeRate`는 `null`
                    - `trend`: 조회한 달을 포함한 최근 4개월 (결제가 없는 달은 0)
                    - `byCategory`: 큰 순서. 카테고리는 **결제 당시** 기준이에요 (나중에 카테고리를 바꿔도 과거 통계는 그대로).

                    에러: `400 VALIDATION_ERROR` — `month` 형식이 틀렸거나 미래의 달
                    """)
    @GetMapping("/summary")
    public ApiResponse<Summary> summary(
            @Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId,
            @Parameter(description = "조회할 월 (YYYY-MM). 생략하면 이번 달", example = "2026-11")
            @RequestParam(required = false) String month) {
        return ApiResponse.of(service.summary(userId, month));
    }
}
