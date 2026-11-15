package com.example.loopcut.subscriptions;

import com.example.loopcut.catalog.CatalogService.CategoryRef;
import com.example.loopcut.common.BusinessException;
import com.example.loopcut.common.Db;
import com.example.loopcut.common.Kst;
import com.example.loopcut.subscriptions.SubscriptionView.PaymentMethodRef;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 구독 조회 + SubscriptionView 변환 (구독 목록 / 상세 / 홈에서 공유) */
@Component
public class SubscriptionReader {

    private static final String SELECT = """
            SELECT s.id, s.service_id, s.name, s.plan_name, sv.logo_url, sv.brand_color,
                   c.id AS category_id, c.code AS category_code, c.name AS category_name,
                   s.amount, s.billing_cycle, s.first_billing_date, s.next_billing_date, s.status,
                   s.unused_flagged_at, s.payment_method_id, pm.name AS pm_name, pm.last4 AS pm_last4,
                   s.notify_enabled, s.notify_days_before, ns.days_before AS default_days_before,
                   s.memo, s.canceled_at, s.created_at
            FROM subscriptions s
            JOIN categories c ON c.id = s.category_id
            LEFT JOIN services sv ON sv.id = s.service_id
            LEFT JOIN payment_methods pm ON pm.id = s.payment_method_id
            LEFT JOIN notification_settings ns ON ns.user_id = s.user_id
            WHERE s.user_id = :uid AND s.deleted_at IS NULL
            """;

    private final JdbcClient jdbc;

    public SubscriptionReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param extraWhere 코드에 박힌 조건 조각만 넘길 것 (예: " AND s.status = 'active'")
     * @param orderBy    코드에 박힌 정렬 조각만 넘길 것
     */
    public List<SubscriptionView> find(long userId, String extraWhere, Map<String, Object> params, String orderBy) {
        Map<String, Object> all = new HashMap<>(params);
        all.put("uid", userId);
        LocalDate today = Kst.today();
        return jdbc.sql(SELECT + extraWhere + " ORDER BY " + orderBy)
                .params(all)
                .query((rs, n) -> map(rs, today))
                .list();
    }

    public SubscriptionView findOne(long userId, long id) {
        return find(userId, " AND s.id = :id", Map.of("id", id), "s.id").stream().findFirst()
                .orElseThrow(SubscriptionReader::notFound);
    }

    public static BusinessException notFound() {
        return BusinessException.notFound("SUBSCRIPTION_NOT_FOUND", "구독을 찾을 수 없어요.");
    }

    private static SubscriptionView map(ResultSet rs, LocalDate today) throws SQLException {
        int amount = rs.getInt("amount");
        String cycle = rs.getString("billing_cycle");
        LocalDate next = Db.date(rs, "next_billing_date");
        List<Integer> own = Db.intList(rs, "notify_days_before");
        List<Integer> fallback = Db.intList(rs, "default_days_before");

        Long pmId = Db.longOrNull(rs, "payment_method_id");
        PaymentMethodRef pm = pmId == null ? null
                : new PaymentMethodRef(Db.id(pmId), rs.getString("pm_name"), rs.getString("pm_last4"));

        return new SubscriptionView(
                Db.id(rs.getLong("id")),
                Db.id(Db.longOrNull(rs, "service_id")),
                rs.getString("name"),
                rs.getString("plan_name"),
                rs.getString("logo_url"),
                rs.getString("brand_color"),
                new CategoryRef(Db.id(rs.getLong("category_id")), rs.getString("category_code"),
                        rs.getString("category_name")),
                amount,
                cycle,
                SubscriptionView.monthlyAmount(amount, cycle),
                Db.date(rs, "first_billing_date"),
                next,
                next == null ? null : ChronoUnit.DAYS.between(today, next),
                rs.getString("status"),
                rs.getObject("unused_flagged_at") != null ? "check_unused" : "in_use",
                pm,
                rs.getBoolean("notify_enabled"),
                own,
                own != null ? own : fallback,
                rs.getString("memo"),
                Db.date(rs, "canceled_at"),
                Db.tsKst(rs, "created_at"));
    }
}
