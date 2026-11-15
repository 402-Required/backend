package com.example.loopcut.notifications;

import com.example.loopcut.common.Body;
import com.example.loopcut.common.BusinessException;
import com.example.loopcut.common.Db;
import com.example.loopcut.common.UpdateBuilder;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    public record Settings(boolean billingAlertEnabled, List<Integer> daysBefore, String notifyTime,
                           boolean pushEnabled, boolean emailEnabled, boolean budgetAlertEnabled) {
    }

    public record Item(String id, String type, String title, String body, String subscriptionId, String sentAt,
                       @JsonProperty("isRead") boolean isRead) {
    }

    public record Page(List<Item> items, String nextCursor) {
    }

    private static final Set<Integer> DAYS = Set.of(0, 1, 3, 7);
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final JdbcClient jdbc;

    public NotificationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ 설정

    public Settings settings(long userId) {
        return jdbc.sql("""
                        SELECT billing_alert_enabled, days_before, notify_time, push_enabled, email_enabled,
                               budget_alert_enabled
                        FROM notification_settings WHERE user_id = :uid
                        """)
                .param("uid", userId)
                .query((rs, n) -> new Settings(rs.getBoolean("billing_alert_enabled"), Db.intList(rs, "days_before"),
                        rs.getObject("notify_time", LocalTime.class).format(HHMM), rs.getBoolean("push_enabled"),
                        rs.getBoolean("email_enabled"), rs.getBoolean("budget_alert_enabled")))
                .optional()
                .orElseGet(() -> {
                    // 설정 행이 없으면(예: 수동 데이터) 기본값으로 만들어 준다
                    jdbc.sql("INSERT INTO notification_settings (user_id) VALUES (:uid) ON CONFLICT DO NOTHING")
                            .param("uid", userId).update();
                    return new Settings(true, List.of(0, 3), "09:00", true, false, true);
                });
    }

    @Transactional
    public Settings updateSettings(long userId, Map<String, Object> raw) {
        Body body = Body.of(raw);
        Boolean billing = body.bool("billingAlertEnabled");
        List<Integer> days = body.intSet("daysBefore", DAYS);
        Boolean push = body.bool("pushEnabled");
        Boolean email = body.bool("emailEnabled");
        Boolean budget = body.bool("budgetAlertEnabled");
        LocalTime time = parseTime(body);
        for (String required : List.of("billingAlertEnabled", "daysBefore", "notifyTime", "pushEnabled",
                "emailEnabled", "budgetAlertEnabled")) {
            if (body.has(required)) {
                body.require(required);
            }
        }
        body.check();

        settings(userId); // 행 보장
        UpdateBuilder update = new UpdateBuilder("notification_settings");
        if (body.has("billingAlertEnabled")) {
            update.set("billing_alert_enabled", billing);
        }
        if (body.has("daysBefore")) {
            update.setRaw("days_before", Db.smallintArray(days));
        }
        if (body.has("notifyTime")) {
            update.set("notify_time", time);
        }
        if (body.has("pushEnabled")) {
            update.set("push_enabled", push);
        }
        if (body.has("emailEnabled")) {
            update.set("email_enabled", email);
        }
        if (body.has("budgetAlertEnabled")) {
            update.set("budget_alert_enabled", budget);
        }
        if (!update.isEmpty()) {
            update.param("uid", userId).execute(jdbc, "user_id = :uid");
        }
        return settings(userId);
    }

    /** HH:mm, 10분 단위만 허용 (알림 배치가 10분마다 돌기 때문) */
    private static LocalTime parseTime(Body body) {
        if (!body.has("notifyTime") || body.isNull("notifyTime")) {
            return null;
        }
        String s = body.string("notifyTime", 5, 5);
        if (s == null) {
            return null;
        }
        try {
            LocalTime t = LocalTime.parse(s, HHMM);
            if (t.getMinute() % 10 != 0) {
                body.error("notifyTime", "10분 단위로만 설정할 수 있어요.");
            }
            return t;
        } catch (DateTimeParseException e) {
            body.error("notifyTime", "HH:mm 형식이어야 해요.");
            return null;
        }
    }

    // ------------------------------------------------------------------ 알림함

    public Page inbox(long userId, String cursor, int limit) {
        Long cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                cursorId = Long.parseLong(cursor);
            } catch (NumberFormatException e) {
                throw BusinessException.validation("cursor", "올바른 cursor가 아니에요.");
            }
        }
        String after = cursorId == null ? "" : """
                 AND (sent_at, id) < (SELECT sent_at, id FROM notifications WHERE id = :cursor AND user_id = :uid)
                """;
        var query = jdbc.sql("""
                        SELECT id, type, title, body, subscription_id, sent_at, read_at FROM notifications
                        WHERE user_id = :uid
                        """ + after + " ORDER BY sent_at DESC, id DESC LIMIT :limit")
                .param("uid", userId).param("limit", limit + 1);
        if (cursorId != null) {
            query = query.param("cursor", cursorId);
        }
        List<Item> rows = query.query((rs, n) -> new Item(Db.id(rs.getLong("id")), rs.getString("type"),
                rs.getString("title"), rs.getString("body"), Db.id(Db.longOrNull(rs, "subscription_id")),
                Db.tsKst(rs, "sent_at"), rs.getObject("read_at") != null)).list();

        boolean hasMore = rows.size() > limit;
        List<Item> items = hasMore ? rows.subList(0, limit) : rows;
        return new Page(items, hasMore ? items.get(items.size() - 1).id() : null);
    }

    public void markRead(long userId, long id) {
        int n = jdbc.sql("UPDATE notifications SET read_at = COALESCE(read_at, now()) WHERE id = :id AND user_id = :uid")
                .param("id", id).param("uid", userId).update();
        if (n == 0) {
            throw BusinessException.notFound("NOTIFICATION_NOT_FOUND", "알림을 찾을 수 없어요.");
        }
    }

    public void markAllRead(long userId) {
        jdbc.sql("UPDATE notifications SET read_at = now() WHERE user_id = :uid AND read_at IS NULL")
                .param("uid", userId).update();
    }

    // ------------------------------------------------------------------ 생성 (배치 / 예산 경고에서 사용)

    /** @return 실제로 INSERT 됐으면 true (유니크 인덱스로 이미 있으면 false) */
    public boolean insert(long userId, Long subscriptionId, String type, String title, String body,
                          LocalDate targetBillingDate, Integer daysBefore) {
        return jdbc.sql("""
                        INSERT INTO notifications (user_id, subscription_id, type, title, body, target_billing_date, days_before)
                        VALUES (:uid, :sid, CAST(:type AS notification_type), :title, :body, :target, :days)
                        ON CONFLICT DO NOTHING
                        """)
                .param("uid", userId).param("sid", subscriptionId).param("type", type).param("title", title)
                .param("body", body).param("target", targetBillingDate).param("days", daysBefore)
                .update() == 1;
    }
}
