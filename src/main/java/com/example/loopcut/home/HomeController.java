package com.example.loopcut.home;

import com.example.loopcut.analytics.SpendingService;
import com.example.loopcut.common.ApiResponse;
import com.example.loopcut.common.AuthInterceptor;
import com.example.loopcut.common.BusinessException;
import com.example.loopcut.common.Kst;
import com.example.loopcut.subscriptions.SubscriptionReader;
import com.example.loopcut.subscriptions.SubscriptionView;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "홈", description = "홈 화면 요약")
@RestController
public class HomeController {

    @Schema(description = "월 예산 현황. 예산을 설정하지 않았으면 home.budget 전체가 null")
    public record Budget(
            @Schema(description = "월 예산(원)", example = "80000") int monthlyBudget,
            @Schema(description = "예산 사용률(%). 이번 달 예상 지출 ÷ 예산, 소수점 버림", example = "84") long usageRatio,
            @Schema(description = "남은 예산(원). 초과하면 음수", example = "13100") long remaining,
            @Schema(description = "ok=여유, warning=예산 주의(경고 비율 이상), over=예산 초과(100% 이상)",
                    allowableValues = {"ok", "warning", "over"}, example = "warning") String status) {
    }

    @Schema(description = "7일 이내 결제 예정")
    public record Upcoming(
            @Schema(description = "건수", example = "3") int count,
            @Schema(description = "금액 합계(원)", example = "27100") long total,
            @Schema(description = "결제일 빠른 순. dDay로 '내일 결제 예정' 배너를 만들 수 있어요.") List<SubscriptionView> items) {
    }

    public record Home(
            @Schema(description = "인사말에 쓰는 사용자 이름", example = "길동") String userName,
            @Schema(description = "기준 월", example = "2026-11") String month,
            @Schema(description = "이번 달 예상 결제액 = 이미 결제된 금액 + 이번 달 남은 결제 예정 금액 (분석 화면의 이번 달 합계와 같은 값)", example = "66900") long expectedTotal,
            @Schema(description = "이용 중인 구독 수", example = "6") long activeCount,
            Budget budget,
            Upcoming upcoming,
            @Schema(description = "알림함의 안 읽은 알림 수 (종 아이콘 뱃지)", example = "2") long unreadNotificationCount) {
    }

    private final JdbcClient jdbc;
    private final SpendingService spending;
    private final SubscriptionReader reader;

    public HomeController(JdbcClient jdbc, SpendingService spending, SubscriptionReader reader) {
        this.jdbc = jdbc;
        this.spending = spending;
        this.reader = reader;
    }

    @Operation(summary = "홈 화면 데이터",
            description = """
                    **화면: 홈**

                    홈에 필요한 값을 한 번에 내려줘요.
                    - 이번 달 예상 결제액, 이용 중인 구독 수
                    - 월 예산 사용률과 상태 (예산을 설정하지 않았으면 `budget`은 `null`)
                    - 7일 이내 결제 예정 구독 목록 — "내일 Canva 결제 예정이에요" 배너는 `upcoming.items`에서 `dDay`가 1인 항목으로 만드세요.
                    - 안 읽은 알림 수 (종 아이콘 뱃지)
                    """)
    @GetMapping("/home")
    public ApiResponse<Home> home(@Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        LocalDate today = Kst.today();

        var user = jdbc.sql("SELECT name, monthly_budget, budget_alert_ratio FROM users WHERE id = :uid")
                .param("uid", userId)
                .query((rs, n) -> new Object[]{rs.getString("name"),
                        rs.getObject("monthly_budget") == null ? null : rs.getInt("monthly_budget"),
                        rs.getInt("budget_alert_ratio")})
                .optional()
                .orElseThrow(() -> BusinessException.notFound("USER_NOT_FOUND", "사용자를 찾을 수 없어요."));

        long expected = spending.expectedTotal(userId, today);
        Integer monthlyBudget = (Integer) user[1];
        int alertRatio = (Integer) user[2];

        Budget budget = null;
        if (monthlyBudget != null) {
            long ratio = monthlyBudget == 0 ? (expected > 0 ? 100 : 0) : expected * 100 / monthlyBudget; // floor
            String status = ratio >= 100 ? "over" : ratio >= alertRatio ? "warning" : "ok";
            budget = new Budget(monthlyBudget, ratio, monthlyBudget - expected, status);
        }

        long activeCount = jdbc.sql("SELECT count(*) FROM subscriptions WHERE user_id = :uid AND status = 'active' AND deleted_at IS NULL")
                .param("uid", userId).query(Long.class).single();

        // 7일 이내 결제 예정
        List<SubscriptionView> upcomingItems = reader.find(userId,
                " AND s.status = 'active' AND s.next_billing_date BETWEEN :today AND :until",
                Map.of("today", today, "until", today.plusDays(7)),
                "s.next_billing_date, s.id");
        long upcomingTotal = upcomingItems.stream().mapToLong(SubscriptionView::amount).sum();

        long unread = jdbc.sql("SELECT count(*) FROM notifications WHERE user_id = :uid AND read_at IS NULL")
                .param("uid", userId).query(Long.class).single();

        return ApiResponse.of(new Home((String) user[0], YearMonth.from(today).toString(), expected, activeCount,
                budget, new Upcoming(upcomingItems.size(), upcomingTotal, upcomingItems), unread));
    }
}
