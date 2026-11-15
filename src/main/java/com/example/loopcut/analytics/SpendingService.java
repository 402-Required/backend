package com.example.loopcut.analytics;

import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** 홈과 분석이 같은 값을 쓰도록 이번 달 예상 지출 계산을 한 곳에 둔다. */
@Service
public class SpendingService {

    private final JdbcClient jdbc;

    public SpendingService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 이번 달 예상 결제액 = 이번 달 이미 기록된 결제 + 오늘부터 이번 달 말까지 결제 예정인 활성 구독 금액 */
    public long expectedTotal(long userId, LocalDate today) {
        LocalDate monthStart = today.withDayOfMonth(1);
        return jdbc.sql("""
                        SELECT
                          (SELECT COALESCE(SUM(p.amount), 0) FROM payments p
                            WHERE p.user_id = :uid AND p.billing_date >= :mStart AND p.billing_date < :mEnd)
                        + (SELECT COALESCE(SUM(s.amount), 0) FROM subscriptions s
                            WHERE s.user_id = :uid AND s.status = 'active' AND s.deleted_at IS NULL
                              AND s.next_billing_date >= :today AND s.next_billing_date < :mEnd)
                        """)
                .param("uid", userId).param("mStart", monthStart).param("mEnd", monthStart.plusMonths(1))
                .param("today", today)
                .query(Long.class).single();
    }

    /** 지난 달 등 확정된 달의 결제 합계 */
    public long paymentsTotal(long userId, LocalDate monthStart) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(amount), 0) FROM payments
                        WHERE user_id = :uid AND billing_date >= :mStart AND billing_date < :mEnd
                        """)
                .param("uid", userId).param("mStart", monthStart).param("mEnd", monthStart.plusMonths(1))
                .query(Long.class).single();
    }
}
