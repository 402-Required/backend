package com.example.loopcut.home;

import com.example.loopcut.analytics.SpendingService;
import com.example.loopcut.common.ApiResponse;
import com.example.loopcut.common.AuthInterceptor;
import com.example.loopcut.common.BusinessException;
import com.example.loopcut.common.Kst;
import com.example.loopcut.subscriptions.SubscriptionReader;
import com.example.loopcut.subscriptions.SubscriptionView;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {

    public record Budget(int monthlyBudget, long usageRatio, long remaining, String status) {
    }

    public record Upcoming(int count, long total, List<SubscriptionView> items) {
    }

    public record Home(String userName, String month, long expectedTotal, long activeCount, Budget budget,
                       Upcoming upcoming, long unreadNotificationCount) {
    }

    private final JdbcClient jdbc;
    private final SpendingService spending;
    private final SubscriptionReader reader;

    public HomeController(JdbcClient jdbc, SpendingService spending, SubscriptionReader reader) {
        this.jdbc = jdbc;
        this.spending = spending;
        this.reader = reader;
    }

    @GetMapping("/home")
    public ApiResponse<Home> home(@RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
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
