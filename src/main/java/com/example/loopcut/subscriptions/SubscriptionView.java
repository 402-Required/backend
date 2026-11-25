package com.example.loopcut.subscriptions;

import com.example.loopcut.catalog.CatalogService.CategoryRef;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/** 7.1 공통 응답 객체 */
@Schema(description = "구독 한 건. 내 구독 목록, 상세, 홈의 다가오는 결제에서 공통으로 써요.")
public record SubscriptionView(
        @Schema(description = "구독 ID (문자열)", example = "12") String id,
        @Schema(description = "카탈로그 서비스 ID. 직접 입력한 구독이면 null", example = "6", nullable = true) String serviceId,
        @Schema(description = "표시 이름", example = "Netflix") String name,
        @Schema(description = "요금제 이름", example = "스탠다드", nullable = true) String planName,
        @Schema(description = "서비스 로고 URL. 없으면 null이니 앱에서 이름 첫 글자로 아이콘을 만드세요.", nullable = true) String logoUrl,
        @Schema(description = "브랜드 색상 (로고가 없을 때 아이콘 배경색)", example = "#E50914", nullable = true) String brandColor,
        CategoryRef category,
        @Schema(description = "결제 금액(원). 연 구독이면 1년치", example = "13500") int amount,
        @Schema(description = "결제 주기", allowableValues = {"monthly", "yearly"}, example = "monthly") String billingCycle,
        @Schema(description = "월 환산 금액. 월 구독은 amount, 연 구독은 amount÷12(반올림)", example = "13500") int monthlyAmount,
        @Schema(description = "첫 결제일 (결제일 계산의 기준)", example = "2026-08-31") LocalDate firstBillingDate,
        @Schema(description = "다음 결제일. 해지한 구독이면 null", example = "2026-11-30", nullable = true) LocalDate nextBillingDate,
        @Schema(description = "다음 결제일까지 남은 일수 (오늘이면 0). 해지한 구독이면 null", example = "5", nullable = true) Long dDay,
        @Schema(description = "구독 상태", allowableValues = {"active", "canceled"}, example = "active") String status,
        @Schema(description = "in_use=이용 중, check_unused=미사용 확인 필요", allowableValues = {"in_use", "check_unused"}, example = "in_use") String usageStatus,
        @Schema(description = "이 구독의 결제 알림 on/off", example = "true") boolean notifyEnabled,
        @Schema(description = "이 구독에만 따로 설정한 알림 시점. null이면 사용자 기본 설정을 따라요.", example = "[3, 0]", nullable = true) List<Integer> notifyDaysBefore,
        @Schema(description = "실제로 적용되는 알림 시점 (구독별 설정이 있으면 그것, 없으면 기본 설정)", example = "[3, 0]") List<Integer> effectiveNotifyDaysBefore,
        @Schema(description = "메모", nullable = true) String memo,
        @Schema(description = "해지일. 이용 중이면 null", example = "2026-11-30", nullable = true) LocalDate canceledAt,
        @Schema(description = "등록 시각 (한국 시간, ISO 8601)", example = "2026-11-15T17:08:47+09:00") String createdAt) {

    /** 월 환산 금액: 월 구독은 amount, 연 구독은 round(amount / 12) */
    public static int monthlyAmount(int amount, String billingCycle) {
        return "yearly".equals(billingCycle) ? (int) Math.round(amount / 12.0) : amount;
    }
}
