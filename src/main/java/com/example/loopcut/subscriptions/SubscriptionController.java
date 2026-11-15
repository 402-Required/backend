package com.example.loopcut.subscriptions;

import com.example.loopcut.common.ApiResponse;
import com.example.loopcut.common.AuthInterceptor;
import com.example.loopcut.notifications.BudgetAlertService;
import com.example.loopcut.subscriptions.SubscriptionService.CreateRequest;
import com.example.loopcut.subscriptions.SubscriptionService.Detail;
import com.example.loopcut.subscriptions.SubscriptionService.History;
import com.example.loopcut.subscriptions.SubscriptionService.ListData;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/subscriptions")
public class SubscriptionController {

    private final SubscriptionService service;
    private final SubscriptionReader reader;
    private final BudgetAlertService budgetAlerts;

    public SubscriptionController(SubscriptionService service, SubscriptionReader reader,
                                  BudgetAlertService budgetAlerts) {
        this.service = service;
        this.reader = reader;
        this.budgetAlerts = budgetAlerts;
    }

    @GetMapping
    public ApiResponse<ListData> list(@RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                      @RequestParam(defaultValue = "active") String status,
                                      @RequestParam(required = false) String categoryCode,
                                      @RequestParam(defaultValue = "nextBillingDate") String sort) {
        return ApiResponse.of(service.list(userId, status, categoryCode, sort));
    }

    @GetMapping("/history")
    public ApiResponse<History> history(@RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        return ApiResponse.of(service.history(userId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Detail> detail(@RequestAttribute(AuthInterceptor.USER_ID) Long userId, @PathVariable long id) {
        return ApiResponse.of(service.detail(userId, id));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SubscriptionView>> create(@RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                                                @Valid @RequestBody CreateRequest request) {
        long id = service.create(userId, request);
        budgetAlerts.checkQuietly(userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(reader.findOne(userId, id)));
    }

    @PatchMapping("/{id}")
    public ApiResponse<SubscriptionView> update(@RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                                @PathVariable long id,
                                                @RequestBody(required = false) Map<String, Object> body) {
        service.update(userId, id, body);
        budgetAlerts.checkQuietly(userId);
        return ApiResponse.of(reader.findOne(userId, id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<SubscriptionView> cancel(@RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                                @PathVariable long id,
                                                @RequestBody(required = false) Map<String, Object> body) {
        service.cancel(userId, id, body);
        return ApiResponse.of(reader.findOne(userId, id));
    }

    @PostMapping("/{id}/resubscribe")
    public ResponseEntity<ApiResponse<SubscriptionView>> resubscribe(
            @RequestAttribute(AuthInterceptor.USER_ID) Long userId, @PathVariable long id,
            @RequestBody(required = false) Map<String, Object> body) {
        long newId = service.resubscribe(userId, id, body);
        budgetAlerts.checkQuietly(userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(reader.findOne(userId, newId)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@RequestAttribute(AuthInterceptor.USER_ID) Long userId, @PathVariable long id) {
        service.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/usage")
    public ApiResponse<SubscriptionView> usage(@RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                               @PathVariable long id,
                                               @RequestBody(required = false) Map<String, Object> body) {
        service.setUnusedFlag(userId, id, body);
        return ApiResponse.of(reader.findOne(userId, id));
    }
}
