package com.example.loopcut.subscriptions;

import com.example.loopcut.catalog.CatalogService.CategoryRef;
import java.time.LocalDate;
import java.util.List;

/** 7.1 공통 응답 객체 */
public record SubscriptionView(
        String id,
        String serviceId,
        String name,
        String planName,
        String logoUrl,
        String brandColor,
        CategoryRef category,
        int amount,
        String billingCycle,
        int monthlyAmount,
        LocalDate firstBillingDate,
        LocalDate nextBillingDate,
        Long dDay,
        String status,
        String usageStatus,
        boolean notifyEnabled,
        List<Integer> notifyDaysBefore,
        List<Integer> effectiveNotifyDaysBefore,
        String memo,
        LocalDate canceledAt,
        String createdAt) {

    /** 월 환산 금액: 월 구독은 amount, 연 구독은 round(amount / 12) */
    public static int monthlyAmount(int amount, String billingCycle) {
        return "yearly".equals(billingCycle) ? (int) Math.round(amount / 12.0) : amount;
    }
}
