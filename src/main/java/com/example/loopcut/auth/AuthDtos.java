package com.example.loopcut.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    /** 필수 동의 여부는 서비스에서 검사 (가입: 400, 구글: 428) */
    @Schema(description = "약관 동의 항목. termsOfService, privacyPolicy는 반드시 true여야 가입돼요.")
    public record Consents(
            @Schema(description = "서비스 이용약관 동의 (필수)", example = "true") Boolean termsOfService,
            @Schema(description = "개인정보 처리방침 동의 (필수)", example = "true") Boolean privacyPolicy,
            @Schema(description = "마케팅 수신 동의 (선택)", example = "false") Boolean marketing) {
    }

    public record SignupRequest(
            @Schema(description = "이메일 (최대 255자). 대소문자는 구분하지 않아요.", example = "gildong@loopcut.app")
            @NotBlank(message = "이메일을 입력해 주세요.") @Email(message = "이메일 형식이 올바르지 않아요.")
            @Size(max = 255, message = "이메일은 255자 이하로 입력해 주세요.") String email,
            @Schema(description = "비밀번호 (8~64자, 영문+숫자 포함)", example = "loopcut123")
            @NotBlank(message = "비밀번호를 입력해 주세요.")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
                    message = "비밀번호는 8~64자, 영문과 숫자를 포함해야 해요.") String password,
            @Schema(description = "이름 (1~50자)", example = "길동")
            @NotBlank(message = "이름을 입력해 주세요.") @Size(max = 50, message = "이름은 50자 이하로 입력해 주세요.") String name,
            @NotNull(message = "약관 동의 정보가 필요해요.") @Valid Consents consents) {
    }

    public record LoginRequest(
            @Schema(description = "가입한 이메일", example = "gildong@loopcut.app")
            @NotBlank(message = "이메일을 입력해 주세요.") String email,
            @Schema(description = "비밀번호", example = "loopcut123")
            @NotBlank(message = "비밀번호를 입력해 주세요.") String password) {
    }

    public record GoogleRequest(
            @Schema(description = "구글 로그인으로 받은 ID 토큰 (JWT 문자열). 앱의 serverClientId는 서버와 같은 '웹' 클라이언트 ID여야 해요.")
            @NotBlank(message = "idToken이 필요해요.") String idToken,
            @Schema(description = "약관 동의. 처음 가입하는 구글 계정일 때만 필요해요. 없으면 428(CONSENT_REQUIRED)이 와요.")
            Consents consents) {
    }

    public record RefreshRequest(
            @Schema(description = "로그인/가입/재발급 때 받은 refresh token")
            @NotBlank(message = "refreshToken이 필요해요.") String refreshToken) {
    }

    public record UserSummary(
            @Schema(description = "사용자 ID (문자열)", example = "1") String id,
            @Schema(example = "gildong@loopcut.app") String email,
            @Schema(example = "길동") String name) {
    }

    @Schema(description = "가입/로그인 결과")
    public record AuthResult(
            @Schema(description = "API 호출에 쓰는 토큰. Authorization: Bearer <accessToken>. 30분 뒤 만료돼요.")
            String accessToken,
            @Schema(description = "accessToken 재발급용 토큰 (30일). 한 번 쓰면 폐기되고 새 토큰이 나와요.")
            String refreshToken,
            UserSummary user,
            @Schema(description = "방금 가입한 계정이면 true") @JsonProperty("isNewUser") boolean isNewUser) {
    }

    @Schema(description = "재발급 결과")
    public record TokenPair(
            @Schema(description = "새 accessToken") String accessToken,
            @Schema(description = "새 refreshToken (이전 토큰은 더 이상 쓸 수 없어요)") String refreshToken) {
    }
}
