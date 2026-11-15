package com.example.loopcut.notifications;

import com.example.loopcut.analytics.SpendingService;
import com.example.loopcut.common.Kst;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** 10.3 예산 경고: 이번 달 예상 지출이 예산 × 경고 비율 이상이 되면 달마다 한 번만 알린다. */
@Service
public class BudgetAlertService {

    private static final Logger log = LoggerFactory.getLogger(BudgetAlertService.class);

    private final JdbcClient jdbc;
    private final SpendingService spending;
    private final NotificationService notifications;
    private final PushService push;

    public BudgetAlertService(JdbcClient jdbc, SpendingService spending, NotificationService notifications,
                              PushService push) {
        this.jdbc = jdbc;
        this.spending = spending;
        this.notifications = notifications;
        this.push = push;
    }

    /** 알림 실패가 구독 등록 같은 본 작업을 실패시키지 않도록 예외를 삼킨다. */
    public void checkQuietly(long userId) {
        try {
            check(userId);
        } catch (RuntimeException e) {
            log.warn("예산 경고 확인 실패 userId={}", userId, e);
        }
    }

    public void check(long userId) {
        var row = jdbc.sql("""
                        SELECT u.monthly_budget, u.budget_alert_ratio,
                               COALESCE(ns.budget_alert_enabled, true) AS enabled, COALESCE(ns.push_enabled, true) AS push
                        FROM users u LEFT JOIN notification_settings ns ON ns.user_id = u.id
                        WHERE u.id = :uid
                        """)
                .param("uid", userId)
                .query((rs, n) -> new Object[]{rs.getObject("monthly_budget") == null ? null : rs.getInt("monthly_budget"),
                        rs.getInt("budget_alert_ratio"), rs.getBoolean("enabled"), rs.getBoolean("push")})
                .optional().orElse(null);
        if (row == null || row[0] == null || !(Boolean) row[2]) {
            return;
        }
        int budget = (Integer) row[0];
        int alertRatio = (Integer) row[1];

        LocalDate today = Kst.today();
        long expected = spending.expectedTotal(userId, today);
        if (budget == 0 ? expected == 0 : expected * 100 < (long) budget * alertRatio) {
            return;
        }

        long already = jdbc.sql("""
                        SELECT count(*) FROM notifications
                        WHERE user_id = :uid AND type = 'budget_warning' AND sent_at >= :monthStart
                        """)
                .param("uid", userId).param("monthStart", Kst.startOfMonth(today))
                .query(Long.class).single();
        if (already > 0) {
            return;
        }

        long ratio = budget == 0 ? 100 : expected * 100 / budget;
        String title = "이번 달 구독 지출이 예산의 " + ratio + "%예요";
        String body = String.format("예상 지출 %,d원 / 월 예산 %,d원", expected, budget);
        notifications.insert(userId, null, "budget_warning", title, body, null, null);
        if ((Boolean) row[3]) {
            push.sendToUser(userId, title, body);
        }
    }
}
