package com.example.loopcut.subscriptions;

import com.example.loopcut.catalog.CatalogService.CategoryRef;
import com.example.loopcut.common.Body;
import com.example.loopcut.common.BusinessException;
import com.example.loopcut.common.Db;
import com.example.loopcut.common.Kst;
import com.example.loopcut.common.UpdateBuilder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionService {

    public static final Set<Integer> NOTIFY_DAYS = Set.of(0, 1, 3, 7);

    // ------------------------------------------------------------------ 요청 / 응답

    public record CreateRequest(
            @Schema(description = "카탈로그에서 고른 서비스의 ID. 직접 입력한 구독이면 생략", example = "6")
            Long serviceId,
            @Schema(description = "표시 이름 (1~100자). 카탈로그 서비스를 골랐다면 서비스 이름을 넣어요.", example = "Netflix")
            @NotBlank(message = "서비스 이름을 입력해 주세요.") @Size(max = 100, message = "이름은 100자 이하로 입력해 주세요.") String name,
            @Schema(description = "요금제 이름 (선택, 50자 이하)", example = "스탠다드")
            @Size(max = 50, message = "요금제 이름은 50자 이하로 입력해 주세요.") String planName,
            @Schema(description = "카테고리 ID (GET /categories의 id)", example = "1")
            @NotNull(message = "카테고리를 선택해 주세요.") Long categoryId,
            @Schema(description = "결제 금액(원). 연 구독이면 1년치 금액", example = "13500")
            @NotNull(message = "금액을 입력해 주세요.") @Min(value = 0, message = "금액은 0원 이상이어야 해요.")
            @Max(value = 10_000_000, message = "금액은 1,000만원 이하로 입력해 주세요.") Integer amount,
            @Schema(description = "결제 주기", allowableValues = {"monthly", "yearly"}, example = "monthly")
            @NotBlank(message = "결제 주기를 선택해 주세요.")
            @Pattern(regexp = "monthly|yearly", message = "monthly 또는 yearly여야 해요.") String billingCycle,
            @Schema(description = "첫 결제일 (YYYY-MM-DD). 오늘 기준 과거 10년 ~ 미래 1년 이내. 매달 같은 날짜 결제의 기준이 돼요.", example = "2026-08-31")
            @NotNull(message = "첫 결제일을 입력해 주세요.") LocalDate firstBillingDate,
            @Schema(description = "이 구독의 결제 알림 on/off. 생략하면 true", example = "true")
            Boolean notifyEnabled,
            @Schema(description = "며칠 전에 알릴지 (0=당일, 1, 3, 7). null이면 마이 > 결제 알림 설정을 따라요.", example = "[3, 0]", nullable = true)
            List<Integer> notifyDaysBefore,
            @Schema(description = "메모 (선택, 2000자 이하)", example = "가족 공유")
            @Size(max = 2000, message = "메모는 2000자 이하로 입력해 주세요.") String memo) {
    }

    public record ListData(List<SubscriptionView> items, long totalCount) {
    }

    public record HistorySummary(long canceledCount, long monthlySavings) {
    }

    public record HistoryItem(String id, String name, CategoryRef category, int amount, String billingCycle,
                              LocalDate firstPaidOn, LocalDate canceledAt, long paidCount, long totalSpent) {
    }

    public record History(HistorySummary summary, List<HistoryItem> items) {
    }

    public record PaymentItem(LocalDate billingDate, int amount) {
    }

    public record Detail(@JsonUnwrapped SubscriptionView subscription, List<PaymentItem> recentPayments,
                         long totalPaidCount, long totalSpent) {
    }

    /** 검증을 마친 신규 구독 값 (등록 / 다시 구독 공용) */
    private record NewSub(Long serviceId, String name, String planName, long categoryId,
                          int amount, String cycle, LocalDate first, boolean notifyEnabled,
                          List<Integer> notifyDays, String memo) {
    }

    private record Existing(String status, LocalDate firstBillingDate, String billingCycle) {
    }

    // ------------------------------------------------------------------

    private final JdbcClient jdbc;
    private final SubscriptionReader reader;

    public SubscriptionService(JdbcClient jdbc, SubscriptionReader reader) {
        this.jdbc = jdbc;
        this.reader = reader;
    }

    // ------------------------------------------------------------------ 조회

    public ListData list(long userId, String status, String categoryCode, String sort) {
        if (!Set.of("active", "canceled").contains(status)) {
            throw BusinessException.validation("status", "active 또는 canceled여야 해요.");
        }
        String orderBy = switch (sort) {
            case "nextBillingDate" -> "s.next_billing_date NULLS LAST, s.id";
            case "amount" -> "s.amount DESC, s.id";
            case "name" -> "s.name, s.id";
            default -> throw BusinessException.validation("sort", "nextBillingDate, amount, name 중 하나여야 해요.");
        };

        StringBuilder where = new StringBuilder(" AND s.status = CAST(:status AS subscription_status)");
        Map<String, Object> params = new HashMap<>();
        params.put("status", status);
        if (categoryCode != null && !categoryCode.isBlank()) {
            where.append(" AND c.code = :code");
            params.put("code", categoryCode);
        }

        long total = jdbc.sql("""
                        SELECT count(*) FROM subscriptions
                        WHERE user_id = :uid AND status = 'active' AND deleted_at IS NULL
                        """)
                .param("uid", userId).query(Long.class).single();
        return new ListData(reader.find(userId, where.toString(), params, orderBy), total);
    }

    public History history(long userId) {
        List<HistoryItem> items = jdbc.sql("""
                        SELECT s.id, s.name, c.id AS category_id, c.code AS category_code, c.name AS category_name,
                               s.amount, s.billing_cycle, MIN(p.billing_date) AS first_paid, s.canceled_at,
                               COUNT(p.id) AS paid_count, COALESCE(SUM(p.amount), 0) AS total_spent
                        FROM subscriptions s
                        JOIN categories c ON c.id = s.category_id
                        LEFT JOIN payments p ON p.subscription_id = s.id
                        WHERE s.user_id = :uid AND s.status = 'canceled' AND s.deleted_at IS NULL
                        GROUP BY s.id, c.id
                        ORDER BY s.canceled_at DESC, s.id DESC
                        """)
                .param("uid", userId)
                .query((rs, n) -> new HistoryItem(Db.id(rs.getLong("id")), rs.getString("name"),
                        new CategoryRef(Db.id(rs.getLong("category_id")), rs.getString("category_code"),
                                rs.getString("category_name")),
                        rs.getInt("amount"), rs.getString("billing_cycle"), Db.date(rs, "first_paid"),
                        Db.date(rs, "canceled_at"), rs.getLong("paid_count"), rs.getLong("total_spent")))
                .list();
        long savings = items.stream()
                .mapToLong(i -> SubscriptionView.monthlyAmount(i.amount(), i.billingCycle())).sum();
        return new History(new HistorySummary(items.size(), savings), items);
    }

    public Detail detail(long userId, long id) {
        SubscriptionView view = reader.findOne(userId, id);
        List<PaymentItem> recent = jdbc.sql("""
                        SELECT billing_date, amount FROM payments
                        WHERE subscription_id = :sid AND user_id = :uid
                        ORDER BY billing_date DESC LIMIT 12
                        """)
                .param("sid", id).param("uid", userId)
                .query((rs, n) -> new PaymentItem(Db.date(rs, "billing_date"), rs.getInt("amount")))
                .list();
        long[] totals = jdbc.sql("""
                        SELECT count(*) AS cnt, COALESCE(SUM(amount), 0) AS total FROM payments
                        WHERE subscription_id = :sid AND user_id = :uid
                        """)
                .param("sid", id).param("uid", userId)
                .query((rs, n) -> new long[]{rs.getLong("cnt"), rs.getLong("total")})
                .single();
        return new Detail(view, recent, totals[0], totals[1]);
    }

    // ------------------------------------------------------------------ 등록

    /** @return 새 구독 ID */
    @Transactional
    public long create(long userId, CreateRequest r) {
        Map<String, String> errors = new LinkedHashMap<>();
        List<Integer> days = r.notifyDaysBefore() == null ? null
                : Body.normalizeDays("notifyDaysBefore", r.notifyDaysBefore(), NOTIFY_DAYS, errors);
        validateFirstBillingDate(r.firstBillingDate(), errors);
        if (!errors.isEmpty()) {
            throw BusinessException.validation(errors);
        }
        return insert(userId, new NewSub(r.serviceId(), r.name().trim(), blankToNull(r.planName()), r.categoryId(),
                r.amount(), r.billingCycle(), r.firstBillingDate(),
                r.notifyEnabled() == null || r.notifyEnabled(), days, blankToNull(r.memo())));
    }

    private long insert(long userId, NewSub s) {
        checkReferences(s.serviceId(), s.categoryId());
        LocalDate today = Kst.today();

        // 오늘(포함)까지의 결제는 아래에서 payments로 채우므로, 다음 결제일은 오늘 "이후"
        long id = jdbc.sql("""
                        INSERT INTO subscriptions (user_id, service_id, name, plan_name, category_id,
                                                   amount, billing_cycle, first_billing_date, next_billing_date,
                                                   notify_enabled, notify_days_before, memo)
                        VALUES (:uid, :serviceId, :name, :plan, :categoryId, :amount,
                                CAST(:cycle AS billing_cycle), :first,
                                calc_next_billing_date(CAST(:first AS date), CAST(:cycle AS billing_cycle), CAST(:today AS date)),
                                :notify, %s, :memo)
                        RETURNING id
                        """.formatted(Db.smallintArray(s.notifyDays())))
                .param("uid", userId).param("serviceId", s.serviceId()).param("name", s.name())
                .param("plan", s.planName()).param("categoryId", s.categoryId())
                .param("amount", s.amount()).param("cycle", s.cycle()).param("first", s.first())
                .param("today", today).param("notify", s.notifyEnabled()).param("memo", s.memo())
                .query(Long.class).single();

        backfillPayments(userId, id, s, today);
        return id;
    }

    /** 과거 결제 내역 채우기: 첫 결제일부터 오늘(포함)까지, 가장 최근 24건 */
    private void backfillPayments(long userId, long subscriptionId, NewSub s, LocalDate today) {
        int step = "yearly".equals(s.cycle()) ? 12 : 1;
        jdbc.sql("""
                        INSERT INTO payments (subscription_id, user_id, category_id, billing_date, amount)
                        SELECT CAST(:sid AS bigint), CAST(:uid AS bigint), CAST(:cat AS smallint),
                               t.d, CAST(:amount AS integer)
                        FROM (SELECT (CAST(:first AS date) + make_interval(months => n * CAST(:step AS int)))::date AS d
                              FROM generate_series(0, 130) AS n) t
                        WHERE t.d <= CAST(:today AS date)
                        ORDER BY t.d DESC
                        LIMIT 24
                        ON CONFLICT (subscription_id, billing_date) DO NOTHING
                        """)
                .param("sid", subscriptionId).param("uid", userId).param("cat", s.categoryId())
                .param("amount", s.amount())
                .param("first", s.first()).param("step", step).param("today", today)
                .update();
    }

    // ------------------------------------------------------------------ 수정

    @Transactional
    public void update(long userId, long id, Map<String, Object> raw) {
        Existing current = lock(userId, id);
        if ("canceled".equals(current.status())) {
            throw BusinessException.conflict("SUBSCRIPTION_CANCELED", "해지한 구독은 수정할 수 없어요.");
        }

        Body body = Body.of(raw);
        String name = body.string("name", 1, 100);
        String planName = body.string("planName", 0, 50);
        Long serviceId = body.id("serviceId");
        Long categoryId = body.id("categoryId");
        Integer amount = body.integer("amount", 0, 10_000_000);
        String cycle = body.oneOf("billingCycle", "monthly", "yearly");
        LocalDate first = body.date("firstBillingDate");
        Boolean notifyEnabled = body.bool("notifyEnabled");
        List<Integer> days = body.intSet("notifyDaysBefore", NOTIFY_DAYS);
        String memo = body.string("memo", 0, 2000);
        for (String required : List.of("name", "categoryId", "amount", "billingCycle", "firstBillingDate", "notifyEnabled")) {
            if (body.has(required)) {
                body.require(required);
            }
        }
        if (first != null) {
            Map<String, String> errors = new LinkedHashMap<>();
            validateFirstBillingDate(first, errors);
            errors.forEach(body::error);
        }
        body.check();

        checkReferences(serviceId, categoryId);

        UpdateBuilder update = new UpdateBuilder("subscriptions");
        if (body.has("name")) {
            update.set("name", name);
        }
        if (body.has("planName")) {
            update.set("plan_name", blankToNull(planName));
        }
        if (body.has("serviceId")) {
            update.set("service_id", serviceId);
        }
        if (body.has("categoryId")) {
            update.set("category_id", categoryId);
        }
        if (body.has("amount")) { // 다음 결제부터 적용. 과거 payments는 그대로
            update.set("amount", amount);
        }
        if (body.has("notifyEnabled")) {
            update.set("notify_enabled", notifyEnabled);
        }
        if (body.has("notifyDaysBefore")) {
            update.setRaw("notify_days_before", Db.smallintArray(days));
        }
        if (body.has("memo")) {
            update.set("memo", blankToNull(memo));
        }
        if (body.has("billingCycle")) {
            update.setCast("billing_cycle", cycle, "billing_cycle");
        }
        if (body.has("firstBillingDate")) {
            update.set("first_billing_date", first);
        }
        if (body.has("firstBillingDate") || body.has("billingCycle")) {
            update.setRaw("next_billing_date", """
                    calc_next_billing_date(CAST(:nbFirst AS date), CAST(:nbCycle AS billing_cycle), CAST(:nbAfter AS date))""")
                    .param("nbFirst", first != null ? first : current.firstBillingDate())
                    .param("nbCycle", cycle != null ? cycle : current.billingCycle())
                    .param("nbAfter", Kst.today().minusDays(1));
        }
        if (!update.isEmpty()) {
            update.param("sid", id).param("uid", userId).execute(jdbc, "id = :sid AND user_id = :uid");
        }
    }

    // ------------------------------------------------------------------ 해지 / 재구독 / 삭제 / 사용 표시

    @Transactional
    public void cancel(long userId, long id, Map<String, Object> raw) {
        Existing current = lock(userId, id);
        if ("canceled".equals(current.status())) {
            throw BusinessException.conflict("SUBSCRIPTION_ALREADY_CANCELED", "이미 해지한 구독이에요.");
        }
        Body body = Body.of(raw);
        LocalDate canceledAt = body.date("canceledAt");
        body.check();

        jdbc.sql("""
                        UPDATE subscriptions
                        SET status = 'canceled', canceled_at = :at, next_billing_date = NULL
                        WHERE id = :id AND user_id = :uid
                        """)
                .param("at", canceledAt != null ? canceledAt : Kst.today())
                .param("id", id).param("uid", userId).update();
    }

    /** 해지한 구독을 되살리지 않고 새 구독을 만든다. @return 새 구독 ID */
    @Transactional
    public long resubscribe(long userId, long id, Map<String, Object> raw) {
        Body body = Body.of(raw);
        LocalDate first = body.date("firstBillingDate");
        Integer amount = body.integer("amount", 0, 10_000_000);
        body.require("firstBillingDate");
        if (first != null) {
            Map<String, String> errors = new LinkedHashMap<>();
            validateFirstBillingDate(first, errors);
            errors.forEach(body::error);
        }
        body.check();

        var old = jdbc.sql("""
                        SELECT status, service_id, name, plan_name, category_id, amount, billing_cycle,
                               notify_enabled, notify_days_before
                        FROM subscriptions WHERE id = :id AND user_id = :uid AND deleted_at IS NULL
                        """)
                .param("id", id).param("uid", userId)
                .query((rs, n) -> {
                    if (!"canceled".equals(rs.getString("status"))) {
                        throw BusinessException.conflict("SUBSCRIPTION_NOT_CANCELED", "해지한 구독만 다시 구독할 수 있어요.");
                    }
                    return new NewSub(Db.longOrNull(rs, "service_id"), rs.getString("name"), rs.getString("plan_name"),
                            rs.getLong("category_id"), rs.getInt("amount"),
                            rs.getString("billing_cycle"), null, rs.getBoolean("notify_enabled"),
                            Db.intList(rs, "notify_days_before"), null);
                })
                .optional()
                .orElseThrow(SubscriptionReader::notFound);

        return insert(userId, new NewSub(old.serviceId(), old.name(), old.planName(), old.categoryId(),
                amount != null ? amount : old.amount(), old.cycle(), first,
                old.notifyEnabled(), old.notifyDays(), null));
    }

    /** "관리 목록에서 삭제". 해지와 별개이며 과거 payments는 남겨 통계에 반영한다. */
    @Transactional
    public void delete(long userId, long id) {
        int n = jdbc.sql("UPDATE subscriptions SET deleted_at = now() WHERE id = :id AND user_id = :uid AND deleted_at IS NULL")
                .param("id", id).param("uid", userId).update();
        if (n == 0) {
            throw SubscriptionReader.notFound();
        }
    }

    @Transactional
    public void setUnusedFlag(long userId, long id, Map<String, Object> raw) {
        Body body = Body.of(raw);
        Boolean check = body.bool("checkUnused");
        body.require("checkUnused");
        body.check();

        int n = jdbc.sql("""
                        UPDATE subscriptions
                        SET unused_flagged_at = CASE WHEN :flag THEN COALESCE(unused_flagged_at, now()) ELSE NULL END
                        WHERE id = :id AND user_id = :uid AND deleted_at IS NULL
                        """)
                .param("flag", check).param("id", id).param("uid", userId).update();
        if (n == 0) {
            throw SubscriptionReader.notFound();
        }
    }

    // ------------------------------------------------------------------ 내부

    private Existing lock(long userId, long id) {
        return jdbc.sql("""
                        SELECT status, first_billing_date, billing_cycle FROM subscriptions
                        WHERE id = :id AND user_id = :uid AND deleted_at IS NULL FOR UPDATE
                        """)
                .param("id", id).param("uid", userId)
                .query((rs, n) -> new Existing(rs.getString("status"), Db.date(rs, "first_billing_date"),
                        rs.getString("billing_cycle")))
                .optional()
                .orElseThrow(SubscriptionReader::notFound);
    }

    private void checkReferences(Long serviceId, Long categoryId) {
        if (serviceId != null && count("SELECT count(*) FROM services WHERE id = :id AND is_active", serviceId) == 0) {
            throw BusinessException.notFound("SERVICE_NOT_FOUND", "서비스를 찾을 수 없어요.");
        }
        if (categoryId != null && count("SELECT count(*) FROM categories WHERE id = :id", categoryId) == 0) {
            throw BusinessException.notFound("CATEGORY_NOT_FOUND", "카테고리를 찾을 수 없어요.");
        }
    }

    private long count(String sql, long id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }

    /** 첫 결제일: 오늘 기준 과거 10년 ~ 미래 1년 */
    private static void validateFirstBillingDate(LocalDate first, Map<String, String> errors) {
        LocalDate today = Kst.today();
        if (first.isBefore(today.minusYears(10)) || first.isAfter(today.plusYears(1))) {
            errors.put("firstBillingDate", "첫 결제일은 과거 10년 ~ 미래 1년 이내여야 해요.");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
