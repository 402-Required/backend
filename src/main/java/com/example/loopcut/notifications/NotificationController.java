package com.example.loopcut.notifications;

import com.example.loopcut.common.ApiResponse;
import com.example.loopcut.common.AuthInterceptor;
import com.example.loopcut.common.ListResponse;
import com.example.loopcut.notifications.NotificationService.Item;
import com.example.loopcut.notifications.NotificationService.Page;
import com.example.loopcut.notifications.NotificationService.Settings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "알림", description = "결제 알림 설정, 알림함")
@RestController
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @Operation(summary = "결제 알림 설정 조회",
            description = """
                    **화면: 마이 > 결제 알림**

                    - `daysBefore`: 며칠 전에 알릴지 (0=당일, 1, 3, 7)
                    - `notifyTime`: 알림이 생성되는 시각 (`HH:mm`, 10분 단위)
                    - 구독마다 따로 설정한 값이 있으면 그 구독은 이 기본값 대신 그 값을 써요.
                    """)
    @GetMapping("/notification-settings")
    public ApiResponse<Settings> settings(@Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        return ApiResponse.of(service.settings(userId));
    }

    @Operation(summary = "결제 알림 설정 수정",
            description = """
                    **화면: 마이 > 결제 알림**

                    보낸 필드만 바뀌어요.
                    - `daysBefore`: 0, 1, 3, 7 중에서 (빈 배열이면 알림 시점 없음)
                    - `notifyTime`: `HH:mm`, **10분 단위만** 가능 (예: `09:00`, `21:30`). `09:05`는 400
                    - `budgetAlertEnabled`: 예산 경고 알림 on/off
                    - `pushEnabled`, `emailEnabled`: 값은 저장되지만 지금은 푸시·이메일 발송을 하지 않아요 (알림은 알림함에만 쌓여요).

                    수정된 설정 전체가 응답으로 와요.
                    """)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
            examples = @ExampleObject(value = "{ \"billingAlertEnabled\": true, \"daysBefore\": [3, 0], \"notifyTime\": \"09:00\", \"budgetAlertEnabled\": true }")))
    @PatchMapping("/notification-settings")
    public ApiResponse<Settings> updateSettings(@Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                                @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.of(service.updateSettings(userId, body));
    }

    @Operation(summary = "알림함 목록",
            description = """
                    **화면: 홈 > 종 아이콘 (알림함)**

                    최신순이에요. 더 불러오려면 응답의 `meta.nextCursor`를 다음 요청의 `cursor`로 보내세요. `nextCursor`가 `null`이면 마지막 페이지예요.
                    - `type`: `billing_reminder`(결제 알림), `budget_warning`(예산 경고), `unused_check`, `notice`
                    - `subscriptionId`: 눌렀을 때 이동할 구독 (없으면 null)
                    """)
    @GetMapping("/notifications")
    public ListResponse<Item> inbox(
            @Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId,
            @Parameter(description = "이전 응답의 meta.nextCursor. 첫 페이지는 생략") @RequestParam(required = false) String cursor,
            @Parameter(description = "한 번에 가져올 개수 (1~100, 기본 20)") @RequestParam(defaultValue = "20") int limit) {
        Page page = service.inbox(userId, cursor, Math.max(1, Math.min(limit, 100)));
        List<Item> items = page.items();
        return ListResponse.of(items, page.nextCursor());
    }

    @Operation(summary = "알림 읽음 처리",
            description = """
                    **화면: 알림함 (알림을 눌렀을 때)**

                    성공하면 `204`. 에러: `404 NOTIFICATION_NOT_FOUND`
                    """)
    @PatchMapping("/notifications/{id}/read")
    public ResponseEntity<Void> read(@Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                     @Parameter(description = "알림 ID") @PathVariable long id) {
        service.markRead(userId, id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "알림 모두 읽음 처리",
            description = """
                    **화면: 알림함 ("모두 읽음")**

                    성공하면 `204`.
                    """)
    @PostMapping("/notifications/read-all")
    public ResponseEntity<Void> readAll(@Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        service.markAllRead(userId);
        return ResponseEntity.noContent().build();
    }
}
