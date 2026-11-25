package com.example.loopcut.users;

import com.example.loopcut.common.ApiResponse;
import com.example.loopcut.common.AuthInterceptor;
import com.example.loopcut.users.UserService.UserProfile;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "마이", description = "내 정보, 월 예산, 회원 탈퇴")
@RestController
@RequestMapping("/users/me")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "내 정보 조회",
            description = """
                    **화면: 마이 (프로필, 월 예산)**

                    - `daysSinceJoined`: "구독 관리 23일째" 표시용 (가입한 날을 1일째로 셈, 한국 시간 기준)
                    - `monthlyBudget`: 월 예산(원). 설정 안 했으면 `null`
                    - `budgetAlertRatio`: 예산의 몇 %부터 "예산 주의"로 볼지 (기본 80)
                    - `loginMethods`: 이 계정에서 쓸 수 있는 로그인 방법
                    """)
    @GetMapping
    public ApiResponse<UserProfile> me(@Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        return ApiResponse.of(userService.me(userId));
    }

    @Operation(summary = "내 정보 수정",
            description = """
                    **화면: 마이 > 프로필 수정, 예산 설정**

                    보낸 필드만 바뀌어요 (모두 선택).
                    - `name`: 1~50자
                    - `monthlyBudget`: 0 이상 정수, **`null`을 보내면 예산 해제**
                    - `budgetAlertRatio`: 1~100

                    수정된 내 정보 전체가 응답으로 와요.
                    """)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
            examples = @ExampleObject(value = "{ \"name\": \"길동\", \"monthlyBudget\": 80000, \"budgetAlertRatio\": 80 }")))
    @PatchMapping
    public ApiResponse<UserProfile> update(@Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                           @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.of(userService.update(userId, body));
    }

    @Operation(summary = "회원 탈퇴",
            description = """
                    **화면: 마이 > 회원 탈퇴**

                    계정과 모든 데이터(구독, 결제 내역, 알림, 문의 등)를 **즉시 삭제하고 복구할 수 없어요.** 성공하면 `204`.
                    앱의 저장된 토큰도 지우고 로그인 화면으로 보내세요.
                    """)
    @DeleteMapping
    public ResponseEntity<Void> delete(@Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        userService.delete(userId);
        return ResponseEntity.noContent().build();
    }
}
