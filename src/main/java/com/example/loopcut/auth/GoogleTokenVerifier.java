package com.example.loopcut.auth;

import com.example.loopcut.common.BusinessException;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 구글 ID 토큰 검증. 구글의 tokeninfo 엔드포인트가 서명·만료를 검증해 주고,
 * 여기서는 audience(= 서버/Web 클라이언트 ID)와 issuer를 추가로 확인한다.
 */
@Component
public class GoogleTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleTokenVerifier.class);

    public record GoogleProfile(String sub, String email, boolean emailVerified, String name) {
    }

    private final RestClient restClient = RestClient.create();
    private final String webClientId;

    public GoogleTokenVerifier(@Value("${google.web-client-id:}") String webClientId) {
        this.webClientId = webClientId;
    }

    public GoogleProfile verify(String idToken) {
        if (webClientId == null || webClientId.isBlank()) {
            log.error("GOOGLE_WEB_CLIENT_ID가 설정되지 않아 구글 로그인을 처리할 수 없어요.");
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "구글 로그인을 사용할 수 없어요.");
        }
        Map<?, ?> body;
        try {
            body = restClient.get()
                    .uri("https://oauth2.googleapis.com/tokeninfo?id_token={t}", idToken)
                    .retrieve()
                    .body(Map.class);
        } catch (HttpClientErrorException e) {
            throw invalid();
        } catch (RestClientException e) {
            log.error("구글 토큰 검증 호출 실패", e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "구글 로그인을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.");
        }
        if (body == null) {
            throw invalid();
        }

        String aud = str(body.get("aud"));
        String iss = str(body.get("iss"));
        String sub = str(body.get("sub"));
        String email = str(body.get("email"));
        long exp = parseLong(body.get("exp"));
        boolean issOk = "accounts.google.com".equals(iss) || "https://accounts.google.com".equals(iss);
        if (!webClientId.equals(aud) || !issOk || sub == null || email == null
                || exp <= Instant.now().getEpochSecond()) {
            throw invalid();
        }
        return new GoogleProfile(sub, email, "true".equals(str(body.get("email_verified"))), str(body.get("name")));
    }

    private static BusinessException invalid() {
        return BusinessException.unauthorized("INVALID_GOOGLE_TOKEN", "구글 로그인에 실패했어요. 다시 시도해 주세요.");
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static long parseLong(Object o) {
        try {
            return o == null ? 0 : Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
