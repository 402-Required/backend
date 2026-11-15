package com.example.loopcut.paymentmethods;

import com.example.loopcut.common.Body;
import com.example.loopcut.common.BusinessException;
import com.example.loopcut.common.Db;
import com.example.loopcut.common.Kst;
import com.example.loopcut.common.UpdateBuilder;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentMethodService {

    public static final Set<String> TYPES = Set.of("credit_card", "check_card", "bank_account", "easy_pay", "etc");

    public record Item(String id, String type, String name, String issuer, String last4,
                       @JsonProperty("isDefault") boolean isDefault, long activeSubscriptionCount,
                       long thisMonthTotal) {
    }

    public record Listing(List<Item> items, long thisMonthTotal) {
    }

    private static final String SELECT = """
            SELECT pm.id, pm.type, pm.name, pm.issuer, pm.last4, pm.is_default,
                   (SELECT count(*) FROM subscriptions s
                     WHERE s.payment_method_id = pm.id AND s.status = 'active' AND s.deleted_at IS NULL) AS active_count,
                   COALESCE((SELECT SUM(p.amount) FROM payments p
                     WHERE p.payment_method_id = pm.id
                       AND p.billing_date >= :mStart AND p.billing_date < :mEnd), 0) AS total
            FROM payment_methods pm
            WHERE pm.user_id = :uid AND pm.deleted_at IS NULL
            """;

    private static final String ORDER = " ORDER BY pm.is_default DESC, pm.created_at, pm.id";

    private final JdbcClient jdbc;

    public PaymentMethodService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Listing list(long userId) {
        LocalDate mStart = Kst.today().withDayOfMonth(1);
        List<Item> items = jdbc.sql(SELECT + ORDER)
                .param("uid", userId).param("mStart", mStart).param("mEnd", mStart.plusMonths(1))
                .query(PaymentMethodService::map).list();
        long total = jdbc.sql("""
                        SELECT COALESCE(SUM(amount), 0) FROM payments
                        WHERE user_id = :uid AND billing_date >= :mStart AND billing_date < :mEnd
                        """)
                .param("uid", userId).param("mStart", mStart).param("mEnd", mStart.plusMonths(1))
                .query(Long.class).single();
        return new Listing(items, total);
    }

    @Transactional
    public Item create(long userId, String type, String name, String issuer, String last4, boolean makeDefault) {
        long existing = jdbc.sql("SELECT count(*) FROM payment_methods WHERE user_id = :uid AND deleted_at IS NULL")
                .param("uid", userId).query(Long.class).single();
        boolean isDefault = makeDefault || existing == 0;
        if (isDefault) {
            clearDefault(userId); // 부분 유니크 인덱스 때문에 기존 기본 해제 → 새로 설정 순서
        }
        long id = jdbc.sql("""
                        INSERT INTO payment_methods (user_id, type, name, issuer, last4, is_default)
                        VALUES (:uid, CAST(:type AS payment_method_type), :name, :issuer, :last4, :def) RETURNING id
                        """)
                .param("uid", userId).param("type", type).param("name", name).param("issuer", issuer)
                .param("last4", last4).param("def", isDefault)
                .query(Long.class).single();
        return find(userId, id);
    }

    @Transactional
    public Item update(long userId, long id, Map<String, Object> raw) {
        requireOwned(userId, id);
        Body body = Body.of(raw);
        String name = body.string("name", 1, 50);
        String issuer = body.string("issuer", 0, 30);
        String last4 = body.string("last4", 0, 4);
        Boolean isDefault = body.bool("isDefault");
        if (body.has("name")) {
            body.require("name");
        }
        if (last4 != null && !last4.matches("\\d{4}")) {
            body.error("last4", "숫자 4자리여야 해요.");
        }
        body.check();

        UpdateBuilder update = new UpdateBuilder("payment_methods");
        if (body.has("name")) {
            update.set("name", name);
        }
        if (body.has("issuer")) {
            update.set("issuer", issuer == null || issuer.isEmpty() ? null : issuer);
        }
        if (body.has("last4")) {
            update.set("last4", last4 == null || last4.isEmpty() ? null : last4);
        }
        // isDefault=false는 무시: 기본 결제수단은 항상 하나 있어야 하므로 다른 결제수단을 기본으로 지정해서 바꾼다.
        if (Boolean.TRUE.equals(isDefault)) {
            clearDefault(userId);
            update.set("is_default", true);
        }
        if (!update.isEmpty()) {
            update.param("uid", userId).param("id", id).execute(jdbc, "id = :id AND user_id = :uid");
        }
        return find(userId, id);
    }

    @Transactional
    public void delete(long userId, long id) {
        boolean wasDefault = jdbc.sql("""
                        SELECT is_default FROM payment_methods
                        WHERE id = :id AND user_id = :uid AND deleted_at IS NULL FOR UPDATE
                        """)
                .param("id", id).param("uid", userId).query(Boolean.class).optional()
                .orElseThrow(PaymentMethodService::notFound);

        jdbc.sql("UPDATE payment_methods SET deleted_at = now(), is_default = false WHERE id = :id")
                .param("id", id).update();
        // 이 결제수단을 쓰던 구독은 연결만 끊는다. 과거 payments는 그대로 둠(지난 통계 유지)
        jdbc.sql("UPDATE subscriptions SET payment_method_id = NULL WHERE payment_method_id = :id")
                .param("id", id).update();

        if (wasDefault) {
            jdbc.sql("""
                            UPDATE payment_methods SET is_default = true
                            WHERE id = (SELECT id FROM payment_methods
                                        WHERE user_id = :uid AND deleted_at IS NULL ORDER BY created_at, id LIMIT 1)
                            """)
                    .param("uid", userId).update();
        }
    }

    // ----------------------------------------------------------------

    private void clearDefault(long userId) {
        jdbc.sql("UPDATE payment_methods SET is_default = false WHERE user_id = :uid AND is_default AND deleted_at IS NULL")
                .param("uid", userId).update();
    }

    private void requireOwned(long userId, long id) {
        long n = jdbc.sql("SELECT count(*) FROM payment_methods WHERE id = :id AND user_id = :uid AND deleted_at IS NULL")
                .param("id", id).param("uid", userId).query(Long.class).single();
        if (n == 0) {
            throw notFound();
        }
    }

    private Item find(long userId, long id) {
        LocalDate mStart = Kst.today().withDayOfMonth(1);
        return jdbc.sql(SELECT + " AND pm.id = :id" + ORDER)
                .param("uid", userId).param("id", id)
                .param("mStart", mStart).param("mEnd", mStart.plusMonths(1))
                .query(PaymentMethodService::map).optional()
                .orElseThrow(PaymentMethodService::notFound);
    }

    private static BusinessException notFound() {
        return BusinessException.notFound("PAYMENT_METHOD_NOT_FOUND", "결제수단을 찾을 수 없어요.");
    }

    private static Item map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Item(Db.id(rs.getLong("id")), rs.getString("type"), rs.getString("name"), rs.getString("issuer"),
                rs.getString("last4"), rs.getBoolean("is_default"), rs.getLong("active_count"), rs.getLong("total"));
    }
}
