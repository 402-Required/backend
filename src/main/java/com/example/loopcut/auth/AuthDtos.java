package com.example.loopcut.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
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
    public record Consents(Boolean termsOfService, Boolean privacyPolicy, Boolean marketing) {
    }

    public record SignupRequest(
            @NotBlank(message = "이메일을 입력해 주세요.") @Email(message = "이메일 형식이 올바르지 않아요.")
            @Size(max = 255, message = "이메일은 255자 이하로 입력해 주세요.") String email,
            @NotBlank(message = "비밀번호를 입력해 주세요.")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
                    message = "비밀번호는 8~64자, 영문과 숫자를 포함해야 해요.") String password,
            @NotBlank(message = "이름을 입력해 주세요.") @Size(max = 50, message = "이름은 50자 이하로 입력해 주세요.") String name,
            @NotNull(message = "약관 동의 정보가 필요해요.") @Valid Consents consents) {
    }

    public record LoginRequest(
            @NotBlank(message = "이메일을 입력해 주세요.") String email,
            @NotBlank(message = "비밀번호를 입력해 주세요.") String password) {
    }

    public record GoogleRequest(
            @NotBlank(message = "idToken이 필요해요.") String idToken,
            Consents consents) {
    }

    public record RefreshRequest(@NotBlank(message = "refreshToken이 필요해요.") String refreshToken) {
    }

    public record UserSummary(String id, String email, String name) {
    }

    public record AuthResult(
            String accessToken,
            String refreshToken,
            UserSummary user,
            @JsonProperty("isNewUser") boolean isNewUser) {
    }

    public record TokenPair(String accessToken, String refreshToken) {
    }
}
