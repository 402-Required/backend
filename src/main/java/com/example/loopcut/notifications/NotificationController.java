package com.example.loopcut.notifications;

import com.example.loopcut.common.ApiResponse;
import com.example.loopcut.common.AuthInterceptor;
import com.example.loopcut.common.ListResponse;
import com.example.loopcut.notifications.NotificationService.Item;
import com.example.loopcut.notifications.NotificationService.Page;
import com.example.loopcut.notifications.NotificationService.Settings;
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

@RestController
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping("/notification-settings")
    public ApiResponse<Settings> settings(@RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        return ApiResponse.of(service.settings(userId));
    }

    @PatchMapping("/notification-settings")
    public ApiResponse<Settings> updateSettings(@RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                                @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.of(service.updateSettings(userId, body));
    }

    @GetMapping("/notifications")
    public ListResponse<Item> inbox(@RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                    @RequestParam(required = false) String cursor,
                                    @RequestParam(defaultValue = "20") int limit) {
        Page page = service.inbox(userId, cursor, Math.max(1, Math.min(limit, 100)));
        List<Item> items = page.items();
        return ListResponse.of(items, page.nextCursor());
    }

    @PatchMapping("/notifications/{id}/read")
    public ResponseEntity<Void> read(@RequestAttribute(AuthInterceptor.USER_ID) Long userId, @PathVariable long id) {
        service.markRead(userId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/notifications/read-all")
    public ResponseEntity<Void> readAll(@RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        service.markAllRead(userId);
        return ResponseEntity.noContent().build();
    }
}
