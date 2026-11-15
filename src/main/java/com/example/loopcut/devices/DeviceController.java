package com.example.loopcut.devices;

import com.example.loopcut.common.AuthInterceptor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 푸시 토큰 등록/해제 */
@RestController
@RequestMapping("/devices")
public class DeviceController {

    public record RegisterRequest(
            @NotBlank(message = "token이 필요해요.") String token,
            @NotBlank(message = "platform이 필요해요.")
            @Pattern(regexp = "ios|android|web", message = "ios, android, web 중 하나여야 해요.") String platform) {
    }

    private final JdbcClient jdbc;

    public DeviceController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** token 기준 upsert. 다른 사용자 소유로 등록돼 있었으면 현재 사용자로 옮긴다. */
    @PostMapping
    public ResponseEntity<Void> register(@RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                         @Valid @RequestBody RegisterRequest request) {
        jdbc.sql("""
                        INSERT INTO device_tokens (user_id, token, platform) VALUES (:uid, :token, :platform)
                        ON CONFLICT (token) DO UPDATE
                          SET user_id = EXCLUDED.user_id, platform = EXCLUDED.platform, last_seen_at = now()
                        """)
                .param("uid", userId).param("token", request.token()).param("platform", request.platform())
                .update();
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{token}")
    public ResponseEntity<Void> unregister(@RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                           @PathVariable String token) {
        jdbc.sql("DELETE FROM device_tokens WHERE token = :token AND user_id = :uid")
                .param("token", token).param("uid", userId).update();
        return ResponseEntity.noContent().build();
    }
}
