package com.example.loopcut.jobs;

import com.example.loopcut.common.Kst;
import com.example.loopcut.notifications.BudgetAlertService;
import com.example.loopcut.notifications.NotificationService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 10장 배치. 모든 Cron은 Asia/Seoul 기준이며, 서버가 여러 대여도 한 인스턴스만 실행되도록
 * 트랜잭션 단위 advisory lock(pg_try_advisory_xact_lock)을 쓴다.
 * (세션 단위 lock은 Neon 풀러에서 다른 커넥션으로 바뀔 수 있어 쓰지 않는다.)
 */
@Component
public class BillingJobs {

    private static final Logger log = LoggerFactory.getLogger(BillingJobs.class);

    private static final long LOCK_RECORD_PAYMENTS = 402_001L;
    private static final long LOCK_SEND_REMINDERS = 402_002L;
    private static final long LOCK_CLEANUP = 402_003L;

    record Reminder(long userId, long subscriptionId, String name, int amount, int daysBefore, LocalDate target) {

        String title() {
            return reminderTitle(daysBefore, name);
        }

        String body() {
            return String.format("%,d원이 결제될 예정이에요.", amount);
        }
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final NotificationService notifications;
    private final BudgetAlertService budgetAlerts;

    public BillingJobs(JdbcClient jdbc, PlatformTransactionManager txManager, NotificationService notifications,
                       BudgetAlertService budgetAlerts) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.notifications = notifications;
        this.budgetAlerts = budgetAlerts;
    }

    // ------------------------------------------------------------------ 10.1 결제 기록 (매일 00:05)

    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Seoul")
    public void recordPayments() {
        Set<Long> affectedUsers = tx.execute(status -> {
            if (!tryLock(LOCK_RECORD_PAYMENTS)) {
                return null;
            }
            return recordDuePayments(Kst.today());
        });
        if (affectedUsers == null) {
            return;
        }
        log.info("결제 기록 배치 완료: 사용자 {}명", affectedUsers.size());
        affectedUsers.forEach(budgetAlerts::checkQuietly);
    }

    /** 영향받은 행이 0이 될 때까지 반복: 서버가 며칠 멈췄어도 밀린 결제가 모두 기록된다. */
    Set<Long> recordDuePayments(LocalDate today) {
        Set<Long> users = new LinkedHashSet<>();
        for (int round = 0; round < 400; round++) {
            List<Long> processed = jdbc.sql("""
                            WITH due AS (
                              SELECT id FROM subscriptions
                              WHERE status = 'active' AND deleted_at IS NULL AND next_billing_date <= :today
                              FOR UPDATE
                            ), ins AS (
                              INSERT INTO payments (subscription_id, user_id, category_id, billing_date, amount)
                              SELECT s.id, s.user_id, s.category_id, s.next_billing_date, s.amount
                              FROM subscriptions s JOIN due USING (id)
                              ON CONFLICT (subscription_id, billing_date) DO NOTHING
                            ), upd AS (
                              UPDATE subscriptions s
                              SET next_billing_date = calc_next_billing_date(s.first_billing_date, s.billing_cycle, s.next_billing_date)
                              FROM due
                              WHERE s.id = due.id
                              RETURNING s.user_id
                            )
                            SELECT user_id FROM upd
                            """)
                    .param("today", today)
                    .query(Long.class).list();
            if (processed.isEmpty()) {
                break;
            }
            users.addAll(processed);
        }
        return users;
    }

    // ------------------------------------------------------------------ 10.2 결제 알림 (10분마다)

    /** 푸시/이메일 발송 없이 알림함(notifications)에만 기록한다. 유니크 인덱스 덕분에 중복 기록되지 않는다. */
    @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Seoul")
    public void createReminders() {
        List<Reminder> inserted = tx.execute(status -> {
            if (!tryLock(LOCK_SEND_REMINDERS)) {
                return null;
            }
            return insertDueReminders(Kst.today(), Kst.nowTime());
        });
        if (inserted != null && !inserted.isEmpty()) {
            log.info("결제 알림 {}건 생성", inserted.size());
        }
    }

    /**
     * 오늘 보낼 알림 대상을 뽑아 notifications에 넣고, 새로 들어간 것만 돌려준다.
     *
     * queries.sql [배치 2]에서 한 가지를 보강했다: 00:05 결제 기록 배치가 next_billing_date를 다음 주기로
     * 넘기기 때문에, "당일(0일 전)" 알림은 next_billing_date - 0 = 오늘 조건으로는 영영 잡히지 않는다.
     * 그래서 days_before = 0이면 "오늘 날짜로 기록된 결제"도 대상으로 본다.
     */
    List<Reminder> insertDueReminders(LocalDate today, LocalTime now) {
        List<Reminder> candidates = jdbc.sql("""
                        SELECT s.user_id, s.id AS sid, s.name, s.amount, d.days_before
                        FROM subscriptions s
                        JOIN notification_settings ns ON ns.user_id = s.user_id AND ns.billing_alert_enabled
                        CROSS JOIN LATERAL unnest(COALESCE(s.notify_days_before, ns.days_before)) AS d(days_before)
                        WHERE s.status = 'active' AND s.deleted_at IS NULL AND s.notify_enabled
                          AND ns.notify_time <= CAST(:now AS time)
                          AND (s.next_billing_date - d.days_before = :today
                               OR (d.days_before = 0 AND EXISTS (
                                     SELECT 1 FROM payments p WHERE p.subscription_id = s.id AND p.billing_date = :today)))
                        """)
                .param("now", now).param("today", today)
                .query((rs, n) -> {
                    int days = rs.getInt("days_before");
                    return new Reminder(rs.getLong("user_id"), rs.getLong("sid"), rs.getString("name"),
                            rs.getInt("amount"), days, today.plusDays(days));
                })
                .list();

        List<Reminder> inserted = new ArrayList<>();
        for (Reminder r : candidates) {
            if (notifications.insert(r.userId(), r.subscriptionId(), "billing_reminder", r.title(), r.body(),
                    r.target(), r.daysBefore())) {
                inserted.add(r);
            }
        }
        return inserted;
    }

    static String reminderTitle(int daysBefore, String name) {
        return switch (daysBefore) {
            case 0 -> "오늘 " + name + " 결제일이에요";
            case 1 -> "내일 " + name + " 결제 예정이에요";
            default -> daysBefore + "일 뒤 " + name + " 결제 예정이에요";
        };
    }

    // ------------------------------------------------------------------ 10.4 정리 (매일 04:00)

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void cleanup() {
        tx.executeWithoutResult(status -> {
            if (!tryLock(LOCK_CLEANUP)) {
                return;
            }
            int tokens = jdbc.sql("""
                            DELETE FROM refresh_tokens
                            WHERE expires_at < now() OR revoked_at < now() - interval '30 days'
                            """).update();
            int notis = jdbc.sql("DELETE FROM notifications WHERE sent_at < now() - interval '180 days'").update();
            log.info("정리 배치: refresh_tokens {}건, notifications {}건 삭제", tokens, notis);
        });
    }

    private boolean tryLock(long key) {
        Supplier<Boolean> lock = () -> jdbc.sql("SELECT pg_try_advisory_xact_lock(:k)")
                .param("k", key).query(Boolean.class).single();
        return Boolean.TRUE.equals(lock.get());
    }
}
