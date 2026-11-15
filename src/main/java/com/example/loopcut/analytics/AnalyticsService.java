package com.example.loopcut.analytics;

import com.example.loopcut.catalog.CatalogService.CategoryRef;
import com.example.loopcut.common.BusinessException;
import com.example.loopcut.common.Db;
import com.example.loopcut.common.Kst;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsService {

    public record MonthTotal(String month, long total) {
    }

    public record CategoryShare(CategoryRef category, long total, int percent) {
    }

    public record Summary(String month, long total, long previousMonthTotal, Integer changeRate, long changeAmount,
                          List<MonthTotal> trend, List<CategoryShare> byCategory, long activeCount) {
    }

    private final JdbcClient jdbc;
    private final SpendingService spending;

    public AnalyticsService(JdbcClient jdbc, SpendingService spending) {
        this.jdbc = jdbc;
        this.spending = spending;
    }

    public Summary summary(long userId, String monthParam) {
        LocalDate today = Kst.today();
        YearMonth current = YearMonth.from(today);
        YearMonth target = parseMonth(monthParam, current);
        boolean isCurrent = target.equals(current);

        LocalDate monthStart = target.atDay(1);
        LocalDate prevStart = target.minusMonths(1).atDay(1);

        long total = isCurrent ? spending.expectedTotal(userId, today) : spending.paymentsTotal(userId, monthStart);
        long prev = spending.paymentsTotal(userId, prevStart);

        // 최근 4개월 (결제 없는 달은 0). 이번 달은 홈의 expectedTotal과 같은 값을 쓴다.
        List<MonthTotal> trend = new ArrayList<>();
        for (int i = 3; i >= 0; i--) {
            YearMonth ym = target.minusMonths(i);
            long value = ym.equals(target) ? total : spending.paymentsTotal(userId, ym.atDay(1));
            trend.add(new MonthTotal(ym.toString(), value));
        }

        long activeCount = jdbc.sql("SELECT count(*) FROM subscriptions WHERE user_id = :uid AND status = 'active' AND deleted_at IS NULL")
                .param("uid", userId).query(Long.class).single();

        return new Summary(target.toString(), total, prev,
                prev == 0 ? null : (int) Math.round((total - prev) * 100.0 / prev), total - prev,
                trend, byCategory(userId, monthStart, isCurrent ? today : null), activeCount);
    }

    /**
     * 카테고리별 지출. 카테고리는 결제 당시 값(payments.category_id) 기준.
     * 이번 달이면 오늘~월말 결제 예정인 활성 구독(subscriptions.category_id)을 더해 total과 합이 맞게 한다.
     */
    private List<CategoryShare> byCategory(long userId, LocalDate monthStart, LocalDate todayIfCurrent) {
        String upcoming = todayIfCurrent == null ? "" : """
                UNION ALL
                SELECT s.category_id, s.amount FROM subscriptions s
                WHERE s.user_id = :uid AND s.status = 'active' AND s.deleted_at IS NULL
                  AND s.next_billing_date >= :today AND s.next_billing_date < :mEnd
                """;
        var query = jdbc.sql("""
                        SELECT c.id, c.code, c.name, SUM(x.amount) AS total
                        FROM (
                          SELECT p.category_id, p.amount FROM payments p
                          WHERE p.user_id = :uid AND p.billing_date >= :mStart AND p.billing_date < :mEnd
                        """ + upcoming + """
                        ) x
                        JOIN categories c ON c.id = x.category_id
                        GROUP BY c.id
                        ORDER BY total DESC, c.id
                        """)
                .param("uid", userId).param("mStart", monthStart).param("mEnd", monthStart.plusMonths(1));
        if (todayIfCurrent != null) {
            query = query.param("today", todayIfCurrent);
        }

        List<Object[]> rows = query.query((rs, n) -> new Object[]{
                new CategoryRef(Db.id(rs.getLong("id")), rs.getString("code"), rs.getString("name")),
                rs.getLong("total")}).list();
        long sum = rows.stream().mapToLong(r -> (Long) r[1]).sum();
        return rows.stream()
                .map(r -> new CategoryShare((CategoryRef) r[0], (Long) r[1],
                        sum == 0 ? 0 : (int) Math.round((Long) r[1] * 100.0 / sum)))
                .toList();
    }

    private static YearMonth parseMonth(String month, YearMonth current) {
        if (month == null || month.isBlank()) {
            return current;
        }
        YearMonth target;
        try {
            target = YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw BusinessException.validation("month", "YYYY-MM 형식이어야 해요.");
        }
        if (target.isAfter(current)) {
            throw BusinessException.validation("month", "미래 달은 조회할 수 없어요.");
        }
        return target;
    }
}
