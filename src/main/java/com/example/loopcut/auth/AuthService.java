package com.example.loopcut.auth;

import com.example.loopcut.auth.AuthDtos.AuthResult;
import com.example.loopcut.auth.AuthDtos.Consents;
import com.example.loopcut.auth.AuthDtos.GoogleRequest;
import com.example.loopcut.auth.AuthDtos.LoginRequest;
import com.example.loopcut.auth.AuthDtos.SignupRequest;
import com.example.loopcut.auth.AuthDtos.TokenPair;
import com.example.loopcut.auth.AuthDtos.UserSummary;
import com.example.loopcut.auth.GoogleTokenVerifier.GoogleProfile;
import com.example.loopcut.common.BusinessException;
import com.example.loopcut.common.Db;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {

    private record UserRow(long id, String email, String name, String passwordHash, String googleSub) {
    }

    /** refresh 트랜잭션 결과 (예외를 트랜잭션 밖에서 던지기 위해 사용 — 재사용 감지 시 revoke가 롤백되면 안 됨) */
    private record RefreshOutcome(Long userId, String refreshToken) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwt;
    private final GoogleTokenVerifier googleVerifier;
    private final TransactionTemplate tx;
    private final long refreshDays;
    private final String termsVersion;

    public AuthService(JdbcClient jdbc, PasswordEncoder passwordEncoder, JwtTokenProvider jwt,
                       GoogleTokenVerifier googleVerifier, PlatformTransactionManager txManager,
                       @Value("${refresh-token.expires-days}") long refreshDays,
                       @Value("${terms.version}") String termsVersion) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        this.googleVerifier = googleVerifier;
        this.tx = new TransactionTemplate(txManager);
        this.refreshDays = refreshDays;
        this.termsVersion = termsVersion;
    }

    // ------------------------------------------------------------------ signup / login

    @Transactional
    public AuthResult signup(SignupRequest req) {
        Consents consents = req.consents();
        if (!requiredConsentsAgreed(consents)) {
            Map<String, String> details = new LinkedHashMap<>();
            if (consents == null || !Boolean.TRUE.equals(consents.termsOfService())) {
                details.put("consents.termsOfService", "필수 동의 항목이에요.");
            }
            if (consents == null || !Boolean.TRUE.equals(consents.privacyPolicy())) {
                details.put("consents.privacyPolicy", "필수 동의 항목이에요.");
            }
            throw BusinessException.validation(details);
        }

        long id = insertUser(req.email().trim(), passwordEncoder.encode(req.password()), null, req.name().trim());
        recordConsents(id, consents);
        createDefaultNotificationSettings(id);

        return result(new UserRow(id, req.email().trim(), req.name().trim(), null, null),true);
    }

    @Transactional
    public AuthResult login(LoginRequest req) {
        UserRow user = findByEmail(req.email().trim());
        if (user == null || user.passwordHash() == null
                || !passwordEncoder.matches(req.password(), user.passwordHash())) {
            throw BusinessException.unauthorized("INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않아요.");
        }
        return result(user,false);
    }

    // ------------------------------------------------------------------ google

    public AuthResult google(GoogleRequest req) {
        GoogleProfile profile = googleVerifier.verify(req.idToken()); // 네트워크 호출은 트랜잭션 밖에서
        return tx.execute(status -> googleInTx(req, profile));
    }

    private AuthResult googleInTx(GoogleRequest req, GoogleProfile profile) {
        UserRow user = jdbc.sql("SELECT id, email::text AS email, name, password_hash, google_sub FROM users WHERE google_sub = :sub")
                .param("sub", profile.sub())
                .query(AuthService::mapUser)
                .optional()
                .orElse(null);
        if (user != null) {
            return result(user,false);
        }

        if (profile.emailVerified()) {
            UserRow sameEmail = findByEmail(profile.email());
            if (sameEmail != null) {
                if (sameEmail.googleSub() != null) {
                    throw BusinessException.conflict("GOOGLE_ACCOUNT_CONFLICT", "이미 다른 구글 계정과 연결된 이메일이에요.");
                }
                jdbc.sql("UPDATE users SET google_sub = :sub WHERE id = :id")
                        .param("sub", profile.sub()).param("id", sameEmail.id()).update();
                return result(sameEmail,false);
            }
        }

        // 신규 가입
        if (!requiredConsentsAgreed(req.consents())) {
            throw new BusinessException(HttpStatus.PRECONDITION_REQUIRED, "CONSENT_REQUIRED",
                    "서비스 이용약관과 개인정보 처리방침에 동의해 주세요.");
        }
        String name = profile.name() != null && !profile.name().isBlank()
                ? profile.name().trim() : profile.email().substring(0, profile.email().indexOf('@'));
        if (name.length() > 50) {
            name = name.substring(0, 50);
        }
        long id = insertUser(profile.email(), null, profile.sub(), name);
        recordConsents(id, req.consents());
        createDefaultNotificationSettings(id);
        return result(new UserRow(id, profile.email(), name, null, profile.sub()),true);
    }

    // ------------------------------------------------------------------ refresh / logout

    public TokenPair refresh(String rawToken) {
        String hash = sha256(rawToken);
        RefreshOutcome outcome = tx.execute(status -> {
            var row = jdbc.sql("""
                            SELECT id, user_id, revoked_at, (expires_at <= now()) AS expired
                            FROM refresh_tokens WHERE token_hash = :h FOR UPDATE
                            """)
                    .param("h", hash)
                    .query((rs, n) -> new Object[]{rs.getLong("id"), rs.getLong("user_id"),
                            rs.getObject("revoked_at") != null, rs.getBoolean("expired")})
                    .optional()
                    .orElse(null);
            if (row == null) {
                return new RefreshOutcome(null, null);
            }
            long tokenId = (Long) row[0];
            long userId = (Long) row[1];
            boolean revoked = (Boolean) row[2];
            boolean expired = (Boolean) row[3];

            if (revoked) {
                // 이미 사용된 토큰의 재사용 = 탈취로 간주 → 이 사용자의 모든 refresh token 폐기
                jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = :uid AND revoked_at IS NULL")
                        .param("uid", userId).update();
                return new RefreshOutcome(null, null);
            }
            if (expired) {
                return new RefreshOutcome(null, null);
            }
            jdbc.sql("UPDATE refresh_tokens SET revoked_at = now(), last_used_at = now() WHERE id = :id")
                    .param("id", tokenId).update();
            return new RefreshOutcome(userId, insertRefreshToken(userId));
        });

        if (outcome == null || outcome.userId() == null) {
            throw BusinessException.unauthorized("INVALID_REFRESH_TOKEN", "다시 로그인해 주세요.");
        }
        return new TokenPair(jwt.createToken(outcome.userId()), outcome.refreshToken());
    }

    @Transactional
    public void logout(long userId, String rawToken) {
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = :h AND user_id = :uid AND revoked_at IS NULL")
                .param("h", sha256(rawToken)).param("uid", userId).update();
    }

    // ------------------------------------------------------------------ internals

    private AuthResult result(UserRow user, boolean isNew) {
        String refresh = insertRefreshToken(user.id());
        return new AuthResult(jwt.createToken(user.id()), refresh,
                new UserSummary(Db.id(user.id()), user.email(), user.name()), isNew);
    }

    private long insertUser(String email, String passwordHash, String googleSub, String name) {
        try {
            return jdbc.sql("""
                            INSERT INTO users (email, password_hash, google_sub, name)
                            VALUES (CAST(:email AS citext), :pw, :sub, :name) RETURNING id
                            """)
                    .param("email", email).param("pw", passwordHash).param("sub", googleSub).param("name", name)
                    .query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("EMAIL_ALREADY_EXISTS", "이미 가입된 이메일이에요.");
        }
    }

    private void recordConsents(long userId, Consents c) {
        insertConsent(userId, "terms_of_service", Boolean.TRUE.equals(c.termsOfService()));
        insertConsent(userId, "privacy_policy", Boolean.TRUE.equals(c.privacyPolicy()));
        insertConsent(userId, "marketing", Boolean.TRUE.equals(c.marketing()));
    }

    private void insertConsent(long userId, String type, boolean agreed) {
        jdbc.sql("""
                        INSERT INTO user_consents (user_id, consent_type, version, agreed)
                        VALUES (:uid, CAST(:type AS consent_type), :v, :agreed)
                        """)
                .param("uid", userId).param("type", type).param("v", termsVersion).param("agreed", agreed)
                .update();
    }

    private void createDefaultNotificationSettings(long userId) {
        jdbc.sql("INSERT INTO notification_settings (user_id) VALUES (:uid)").param("uid", userId).update();
    }

    private String insertRefreshToken(long userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.sql("""
                        INSERT INTO refresh_tokens (user_id, token_hash, expires_at, last_used_at)
                        VALUES (:uid, :h, :exp, now())
                        """)
                .param("uid", userId).param("h", sha256(raw))
                .param("exp", OffsetDateTime.now().plusDays(refreshDays))
                .update();
        return raw;
    }

    private UserRow findByEmail(String email) {
        // citext 비교는 반드시 명시 캐스팅 (varchar 바인딩이면 text 비교로 풀려 대소문자를 구분함)
        return jdbc.sql("SELECT id, email::text AS email, name, password_hash, google_sub FROM users WHERE email = CAST(:email AS citext)")
                .param("email", email)
                .query(AuthService::mapUser)
                .optional()
                .orElse(null);
    }

    private static UserRow mapUser(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new UserRow(rs.getLong("id"), rs.getString("email"), rs.getString("name"),
                rs.getString("password_hash"), rs.getString("google_sub"));
    }

    private static boolean requiredConsentsAgreed(Consents c) {
        return c != null && Boolean.TRUE.equals(c.termsOfService()) && Boolean.TRUE.equals(c.privacyPolicy());
    }

    static String sha256(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
