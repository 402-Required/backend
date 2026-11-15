package com.example.loopcut.notifications;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * FCM 푸시 발송. FIREBASE_SERVICE_ACCOUNT_JSON이 없으면 로그만 남긴다 (로컬 개발용).
 * 알림 발송 실패가 호출한 쪽(요청/배치)을 실패시키지 않도록 예외는 여기서 삼킨다.
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    private final JdbcClient jdbc;
    private final String serviceAccountJson;
    private FirebaseMessaging messaging;

    public PushService(JdbcClient jdbc, @Value("${firebase.service-account-json:}") String serviceAccountJson) {
        this.jdbc = jdbc;
        this.serviceAccountJson = serviceAccountJson;
    }

    @PostConstruct
    void init() {
        if (serviceAccountJson == null || serviceAccountJson.isBlank()) {
            log.warn("FIREBASE_SERVICE_ACCOUNT_JSON이 없어 푸시는 로그로만 남겨요.");
            return;
        }
        try (var in = new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8))) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(in))
                    .build();
            FirebaseApp app = FirebaseApp.getApps().isEmpty() ? FirebaseApp.initializeApp(options)
                    : FirebaseApp.getInstance();
            messaging = FirebaseMessaging.getInstance(app);
        } catch (Exception e) {
            log.error("Firebase 초기화에 실패했어요. 푸시는 로그로만 남겨요.", e);
        }
    }

    public void sendToUser(long userId, String title, String body) {
        List<String> tokens = jdbc.sql("SELECT token FROM device_tokens WHERE user_id = :uid")
                .param("uid", userId).query(String.class).list();
        for (String token : tokens) {
            if (messaging == null) {
                log.info("[push:dry-run] userId={} title={}", userId, title);
                continue;
            }
            try {
                messaging.send(Message.builder()
                        .setToken(token)
                        .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                        .build());
            } catch (FirebaseMessagingException e) {
                if (e.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED) {
                    // 무효 토큰은 삭제
                    jdbc.sql("DELETE FROM device_tokens WHERE token = :t").param("t", token).update();
                } else {
                    log.warn("푸시 발송 실패 userId={} code={}", userId, e.getMessagingErrorCode());
                }
            } catch (RuntimeException e) {
                log.warn("푸시 발송 중 오류 userId={}", userId, e);
            }
        }
    }

    /** 이메일 알림은 1차 구현에서 인터페이스만 두고 로그로 대체 */
    public void sendEmail(long userId, String title, String body) {
        log.info("[email:dry-run] userId={} title={}", userId, title);
    }
}
