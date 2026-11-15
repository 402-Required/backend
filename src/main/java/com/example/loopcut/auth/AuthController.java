package com.example.loopcut.auth;

import com.example.loopcut.auth.AuthDtos.AuthResult;
import com.example.loopcut.auth.AuthDtos.GoogleRequest;
import com.example.loopcut.auth.AuthDtos.LoginRequest;
import com.example.loopcut.auth.AuthDtos.RefreshRequest;
import com.example.loopcut.auth.AuthDtos.SignupRequest;
import com.example.loopcut.auth.AuthDtos.TokenPair;
import com.example.loopcut.common.ApiResponse;
import com.example.loopcut.common.AuthInterceptor;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<AuthResult>> signup(@Valid @RequestBody SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(authService.signup(request)));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResult> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.of(authService.login(request));
    }

    @PostMapping("/google")
    public ResponseEntity<ApiResponse<AuthResult>> google(@Valid @RequestBody GoogleRequest request) {
        AuthResult result = authService.google(request);
        return ResponseEntity.status(result.isNewUser() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.of(result));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenPair> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.of(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                       @Valid @RequestBody RefreshRequest request) {
        authService.logout(userId, request.refreshToken());
        return ResponseEntity.noContent().build();
    }
}
