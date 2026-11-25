package com.example.loopcut.auth;

import com.example.loopcut.auth.AuthDtos.AuthResult;
import com.example.loopcut.auth.AuthDtos.GoogleRequest;
import com.example.loopcut.auth.AuthDtos.LoginRequest;
import com.example.loopcut.auth.AuthDtos.RefreshRequest;
import com.example.loopcut.auth.AuthDtos.SignupRequest;
import com.example.loopcut.auth.AuthDtos.TokenPair;
import com.example.loopcut.common.ApiResponse;
import com.example.loopcut.common.AuthInterceptor;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인증", description = "회원가입, 로그인, 구글 로그인, 토큰 재발급, 로그아웃")
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "이메일 회원가입",
            description = """
                    **화면: 회원가입**
                    - 가입과 동시에 로그인 처리되어 토큰이 함께 내려와요.
                    - 약관 동의(`termsOfService`, `privacyPolicy`)는 필수예요.

                    에러: `400 VALIDATION_ERROR`(입력값 오류, details에 필드별 사유), `409 EMAIL_ALREADY_EXISTS`(이미 가입된 이메일)
                    """)
    @SecurityRequirements
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<AuthResult>> signup(@Valid @RequestBody SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(authService.signup(request)));
    }

    @Operation(summary = "이메일 로그인",
            description = """
                    **화면: 로그인**

                    에러: `401 INVALID_CREDENTIALS` — 이메일이 없거나 비밀번호가 틀리거나 구글 전용 계정일 때 (메시지는 하나로 통일)
                    """)
    @SecurityRequirements
    @PostMapping("/login")
    public ApiResponse<AuthResult> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.of(authService.login(request));
    }

    @Operation(summary = "구글 로그인 / 구글 가입",
            description = """
                    **화면: 로그인, 회원가입 (구글 버튼)**

                    앱에서 구글 로그인으로 받은 `idToken`을 보내요. 서버가 이렇게 처리해요.
                    1. 이미 연결된 구글 계정이면 로그인 (`200`)
                    2. 같은 이메일로 가입한 계정이 있으면 자동 연결 후 로그인 (`200`)
                    3. 처음이면 가입 (`201`, `isNewUser: true`) — 이때 `consents`가 필요해요.

                    에러:
                    - `428 CONSENT_REQUIRED` — 신규 가입인데 약관 동의가 없어요. 약관 동의 화면을 띄운 뒤 **같은 idToken**으로 다시 요청하세요.
                    - `401 INVALID_GOOGLE_TOKEN` — 토큰이 잘못됐거나 만료됐거나, 앱의 `serverClientId`가 서버와 다른 클라이언트 ID예요.
                    - `409 GOOGLE_ACCOUNT_CONFLICT` — 같은 이메일이 이미 다른 구글 계정과 연결돼 있어요.
                    """)
    @SecurityRequirements
    @PostMapping("/google")
    public ResponseEntity<ApiResponse<AuthResult>> google(@Valid @RequestBody GoogleRequest request) {
        AuthResult result = authService.google(request);
        return ResponseEntity.status(result.isNewUser() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.of(result));
    }

    @Operation(summary = "토큰 재발급",
            description = """
                    **화면: 없음 (앱이 자동 호출)** — API가 `401 UNAUTHORIZED`를 주면 이 API로 accessToken을 새로 받고 원래 요청을 다시 보내세요.

                    - 호출할 때마다 refreshToken도 **새로 발급**되고 이전 것은 폐기돼요. 응답의 새 값을 꼭 저장하세요.
                    - 이미 쓴 refreshToken을 다시 보내면 탈취로 보고 그 계정의 **모든 기기가 로그아웃**돼요.

                    에러: `401 INVALID_REFRESH_TOKEN` — 다시 로그인하세요.
                    """)
    @SecurityRequirements
    @PostMapping("/refresh")
    public ApiResponse<TokenPair> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.of(authService.refresh(request.refreshToken()));
    }

    @Operation(summary = "로그아웃",
            description = """
                    **화면: 마이 > 로그아웃**

                    보낸 refreshToken을 폐기해요. 성공하면 `204`(본문 없음). 앱에 저장된 토큰도 지워 주세요.
                    """)
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                       @Valid @RequestBody RefreshRequest request) {
        authService.logout(userId, request.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
