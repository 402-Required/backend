package com.example.loopcut.subscriptions;

import com.example.loopcut.common.ApiResponse;
import com.example.loopcut.common.AuthInterceptor;
import com.example.loopcut.notifications.BudgetAlertService;
import com.example.loopcut.subscriptions.SubscriptionService.CreateRequest;
import com.example.loopcut.subscriptions.SubscriptionService.Detail;
import com.example.loopcut.subscriptions.SubscriptionService.History;
import com.example.loopcut.subscriptions.SubscriptionService.ListData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Tag(name = "구독", description = "구독 등록·조회·수정·해지·삭제, 지난 구독 이력")
@RestController
@RequestMapping("/subscriptions")
public class SubscriptionController {

    private static final String NOT_FOUND = "에러: `404 SUBSCRIPTION_NOT_FOUND` — 없는 구독이거나 **다른 사람의 구독**이에요 (존재 여부를 알려주지 않으려고 같은 에러를 줘요).";

    private final SubscriptionService service;
    private final SubscriptionReader reader;
    private final BudgetAlertService budgetAlerts;

    public SubscriptionController(SubscriptionService service, SubscriptionReader reader,
                                  BudgetAlertService budgetAlerts) {
        this.service = service;
        this.reader = reader;
        this.budgetAlerts = budgetAlerts;
    }

    @Operation(summary = "내 구독 목록",
            description = """
                    **화면: 내 구독 (목록, 카테고리 탭, 정렬)**

                    - `status=active`(기본): 이용 중인 구독 / `status=canceled`: 해지한 구독
                    - `categoryCode`: 카테고리 탭 필터 (`GET /categories`의 `code`)
                    - `sort`: `nextBillingDate`(결제일 빠른 순, 기본) / `amount`(금액 큰 순) / `name`(이름순)
                    - `totalCount`: 카테고리 필터와 상관없는 **이용 중인 구독 전체 수** ("전체 6개")
                    - `dDay`: 다음 결제일까지 남은 일수 (오늘이면 0)
                    - `usageStatus`: `in_use`(이용 중) / `check_unused`(미사용 확인 필요)
                    """)
    @GetMapping
    public ApiResponse<ListData> list(
            @Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId,
            @Parameter(description = "active 또는 canceled", example = "active") @RequestParam(defaultValue = "active") String status,
            @Parameter(description = "카테고리 code (예: video, music)") @RequestParam(required = false) String categoryCode,
            @Parameter(description = "nextBillingDate | amount | name", example = "nextBillingDate") @RequestParam(defaultValue = "nextBillingDate") String sort) {
        return ApiResponse.of(service.list(userId, status, categoryCode, sort));
    }

    @Operation(summary = "지난 구독 이력",
            description = """
                    **화면: 지난 구독 이력**

                    해지한 구독을 해지일 최신순으로 보여줘요.
                    - `summary.canceledCount`: 해지한 구독 수
                    - `summary.monthlySavings`: **해지로 아낀 금액(월 환산)**. 연 구독은 12로 나눠서 합산해요.
                    - 각 항목: `firstPaidOn`(첫 결제일), `paidCount`(결제 횟수), `totalSpent`(총 지출)
                    """)
    @GetMapping("/history")
    public ApiResponse<History> history(@Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        return ApiResponse.of(service.history(userId));
    }

    @Operation(summary = "구독 상세",
            description = """
                    **화면: 구독 상세 (최근 결제 내역 포함)**

                    구독 기본 정보에 아래가 더해져요.
                    - `recentPayments`: 최근 결제 12건 (최신순)
                    - `totalPaidCount`, `totalSpent`: 지금까지 결제 횟수와 총 금액

                    """ + NOT_FOUND)
    @GetMapping("/{id}")
    public ApiResponse<Detail> detail(@Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                      @Parameter(description = "구독 ID") @PathVariable long id) {
        return ApiResponse.of(service.detail(userId, id));
    }

    @Operation(summary = "구독 등록",
            description = """
                    **화면: 구독 추가 > 등록**

                    - 서비스 목록에서 골랐다면 `serviceId`를 보내고, 직접 입력이면 생략해요. 어느 쪽이든 `name`은 필수예요.
                    - **첫 결제일이 과거**면 첫 결제일부터 오늘까지의 결제 내역이 자동으로 채워져요 (최대 24건). 그래서 등록 직후부터 분석 화면과 "최근 결제 내역"에 보여요.
                    - 다음 결제일은 서버가 계산해요. 매달 31일 같은 말일 결제도 월말로 보정돼요 (1/31 → 2/28 → 3/31).
                    - `notifyDaysBefore`: `null`이면 마이 > 결제 알림 설정을 따르고, 값을 주면 이 구독만 따로 알려요 (0, 1, 3, 7 중에서).
                    - 등록 후 이번 달 예산을 넘으면 알림함에 예산 경고가 생겨요.

                    에러: `400 VALIDATION_ERROR`, `404 SERVICE_NOT_FOUND`, `404 CATEGORY_NOT_FOUND`
                    """)
    @PostMapping
    public ResponseEntity<ApiResponse<SubscriptionView>> create(
            @Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId,
            @Valid @RequestBody CreateRequest request) {
        long id = service.create(userId, request);
        budgetAlerts.checkQuietly(userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(reader.findOne(userId, id)));
    }

    @Operation(summary = "구독 수정",
            description = """
                    **화면: 구독 상세 > 수정**

                    **보낸 필드만** 바뀌어요. 필드는 등록과 같아요: `name`, `planName`, `serviceId`, `categoryId`, `amount`, `billingCycle`, `firstBillingDate`, `notifyEnabled`, `notifyDaysBefore`, `memo`
                    - `planName`, `serviceId`, `notifyDaysBefore`, `memo`는 `null`을 보내면 비워져요.
                    - `firstBillingDate`나 `billingCycle`을 바꾸면 **다음 결제일이 다시 계산**돼요. 과거 결제 내역은 그대로예요.
                    - `amount`를 바꾸면 **다음 결제부터** 적용돼요 (과거 결제 금액은 안 바뀌어요).

                    에러: `409 SUBSCRIPTION_CANCELED` — 해지한 구독은 수정할 수 없어요.
                    """ + "\n\n" + NOT_FOUND)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
            examples = @ExampleObject(value = "{ \"amount\": 15000, \"memo\": \"가족 공유\", \"notifyDaysBefore\": [1, 7] }")))
    @PatchMapping("/{id}")
    public ApiResponse<SubscriptionView> update(@Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                                @Parameter(description = "구독 ID") @PathVariable long id,
                                                @RequestBody(required = false) Map<String, Object> body) {
        service.update(userId, id, body);
        budgetAlerts.checkQuietly(userId);
        return ApiResponse.of(reader.findOne(userId, id));
    }

    @Operation(summary = "구독 해지",
            description = """
                    **화면: 구독 상세 > 해지**

                    구독이 **지난 구독 이력**으로 옮겨지고 더 이상 결제 알림이 안 와요. 본문은 비워도 돼요.
                    - `canceledAt`(선택, `YYYY-MM-DD`): 해지일. 생략하면 오늘.

                    에러: `409 SUBSCRIPTION_ALREADY_CANCELED`
                    """ + "\n\n" + NOT_FOUND)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = false, content = @Content(mediaType = "application/json",
            examples = @ExampleObject(value = "{ \"canceledAt\": \"2026-11-30\" }")))
    @PostMapping("/{id}/cancel")
    public ApiResponse<SubscriptionView> cancel(@Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                                @Parameter(description = "구독 ID") @PathVariable long id,
                                                @RequestBody(required = false) Map<String, Object> body) {
        service.cancel(userId, id, body);
        return ApiResponse.of(reader.findOne(userId, id));
    }

    @Operation(summary = "다시 구독",
            description = """
                    **화면: 지난 구독 이력 > 다시 구독**

                    해지한 구독을 되살리지 않고 **새 구독을 만들어요** (이전 이용 기간 기록이 섞이지 않게). 이름·서비스·카테고리·결제 주기·알림 설정은 기존 값을 복사해요.
                    - `firstBillingDate`(필수): 새 첫 결제일
                    - `amount`(선택): 금액이 바뀌었으면 보내요. 생략하면 기존 금액.

                    응답은 **새 구독**(새 `id`)이에요.

                    에러: `409 SUBSCRIPTION_NOT_CANCELED` — 해지한 구독에만 쓸 수 있어요.
                    """ + "\n\n" + NOT_FOUND)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
            examples = @ExampleObject(value = "{ \"firstBillingDate\": \"2026-12-01\", \"amount\": 7900 }")))
    @PostMapping("/{id}/resubscribe")
    public ResponseEntity<ApiResponse<SubscriptionView>> resubscribe(
            @Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId,
            @Parameter(description = "해지한 구독의 ID") @PathVariable long id,
            @RequestBody(required = false) Map<String, Object> body) {
        long newId = service.resubscribe(userId, id, body);
        budgetAlerts.checkQuietly(userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(reader.findOne(userId, newId)));
    }

    @Operation(summary = "구독 삭제 (관리 목록에서 삭제)",
            description = """
                    **화면: 구독 상세 > 관리 목록에서 삭제**

                    **해지와는 달라요.** 목록과 이력에서 모두 사라지지만, 지금까지의 결제 내역은 남아서 분석 통계에는 계속 반영돼요 (실제로 쓴 돈이니까요). 성공하면 `204`.

                    """ + NOT_FOUND)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                       @Parameter(description = "구독 ID") @PathVariable long id) {
        service.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "미사용 확인 필요 표시 켜기/끄기",
            description = """
                    **화면: 내 구독, 홈 (미사용 확인 필요 뱃지)**

                    - `checkUnused: true`: 뱃지를 켜요 (`usageStatus`가 `check_unused`로 바뀜)
                    - `checkUnused: false`: 끄고 `in_use`로 돌려요

                    """ + NOT_FOUND)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
            examples = @ExampleObject(value = "{ \"checkUnused\": true }")))
    @PatchMapping("/{id}/usage")
    public ApiResponse<SubscriptionView> usage(@Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                               @Parameter(description = "구독 ID") @PathVariable long id,
                                               @RequestBody(required = false) Map<String, Object> body) {
        service.setUnusedFlag(userId, id, body);
        return ApiResponse.of(reader.findOne(userId, id));
    }
}
