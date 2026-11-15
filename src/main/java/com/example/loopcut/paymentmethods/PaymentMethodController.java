package com.example.loopcut.paymentmethods;

import com.example.loopcut.common.ApiResponse;
import com.example.loopcut.common.AuthInterceptor;
import com.example.loopcut.paymentmethods.PaymentMethodService.Item;
import com.example.loopcut.paymentmethods.PaymentMethodService.Listing;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payment-methods")
public class PaymentMethodController {

    public record CreateRequest(
            @NotBlank(message = "결제수단 종류를 선택해 주세요.")
            @Pattern(regexp = "credit_card|check_card|bank_account|easy_pay|etc",
                    message = "credit_card, check_card, bank_account, easy_pay, etc 중 하나여야 해요.") String type,
            @NotBlank(message = "이름을 입력해 주세요.") @Size(max = 50, message = "이름은 50자 이하로 입력해 주세요.") String name,
            @Size(max = 30, message = "발급사는 30자 이하로 입력해 주세요.") String issuer,
            @Pattern(regexp = "\\d{4}", message = "카드 끝 4자리 숫자만 입력해 주세요.") String last4,
            @JsonProperty("isDefault") Boolean isDefault) {
    }

    private final PaymentMethodService service;

    public PaymentMethodController(PaymentMethodService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<Listing> list(@RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        return ApiResponse.of(service.list(userId));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Item>> create(@RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                                    @Valid @RequestBody CreateRequest r) {
        Item item = service.create(userId, r.type(), r.name().trim(), r.issuer(), r.last4(),
                Boolean.TRUE.equals(r.isDefault()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(item));
    }

    @PatchMapping("/{id}")
    public ApiResponse<Item> update(@RequestAttribute(AuthInterceptor.USER_ID) Long userId, @PathVariable long id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.of(service.update(userId, id, body));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@RequestAttribute(AuthInterceptor.USER_ID) Long userId, @PathVariable long id) {
        service.delete(userId, id);
        return ResponseEntity.noContent().build();
    }
}
