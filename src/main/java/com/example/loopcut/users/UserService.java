package com.example.loopcut.users;

import com.example.loopcut.common.Body;
import com.example.loopcut.common.BusinessException;
import com.example.loopcut.common.Db;
import com.example.loopcut.common.Kst;
import com.example.loopcut.common.UpdateBuilder;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    public record LoginMethods(boolean password, boolean google) {
    }

    public record UserProfile(String id, String email, String name, Integer monthlyBudget, int budgetAlertRatio,
                              long daysSinceJoined, LoginMethods loginMethods, String createdAt) {
    }

    private final JdbcClient jdbc;

    public UserService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UserProfile me(long userId) {
        return jdbc.sql("""
                        SELECT id, email::text AS email, name, monthly_budget, budget_alert_ratio,
                               (password_hash IS NOT NULL) AS has_password, (google_sub IS NOT NULL) AS has_google,
                               created_at
                        FROM users WHERE id = :uid
                        """)
                .param("uid", userId)
                .query((rs, n) -> {
                    OffsetDateTime created = Db.ts(rs, "created_at");
                    long days = ChronoUnit.DAYS.between(created.atZoneSameInstant(Kst.ZONE).toLocalDate(), Kst.today()) + 1;
                    return new UserProfile(Db.id(rs.getLong("id")), rs.getString("email"), rs.getString("name"),
                            Db.intOrNull(rs, "monthly_budget"), rs.getInt("budget_alert_ratio"), days,
                            new LoginMethods(rs.getBoolean("has_password"), rs.getBoolean("has_google")),
                            Kst.fmt(created));
                })
                .optional()
                .orElseThrow(() -> BusinessException.notFound("USER_NOT_FOUND", "사용자를 찾을 수 없어요."));
    }

    @Transactional
    public UserProfile update(long userId, Map<String, Object> raw) {
        Body body = Body.of(raw);
        String name = body.string("name", 1, 50);
        Integer budget = body.integer("monthlyBudget", 0, Integer.MAX_VALUE);
        Integer ratio = body.integer("budgetAlertRatio", 1, 100);
        if (body.has("name")) {
            body.require("name");
        }
        if (body.has("budgetAlertRatio")) {
            body.require("budgetAlertRatio");
        }
        body.check();

        UpdateBuilder update = new UpdateBuilder("users");
        if (body.has("name")) {
            update.set("name", name);
        }
        if (body.has("monthlyBudget")) { // null = 예산 해제
            update.set("monthly_budget", budget);
        }
        if (body.has("budgetAlertRatio")) {
            update.set("budget_alert_ratio", ratio);
        }
        if (!update.isEmpty()) {
            update.param("uid", userId).execute(jdbc, "id = :uid");
        }
        return me(userId);
    }

    /** 회원 탈퇴: 행을 실제로 삭제 (FK ON DELETE CASCADE로 하위 데이터 함께 삭제) */
    @Transactional
    public void delete(long userId) {
        jdbc.sql("DELETE FROM users WHERE id = :uid").param("uid", userId).update();
    }
}
