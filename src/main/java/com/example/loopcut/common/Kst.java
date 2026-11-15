package com.example.loopcut.common;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * "오늘"은 항상 KST 기준. Neon 풀러에서는 세션 타임존 설정이 유지되지 않으므로
 * SQL의 current_date 대신 여기서 계산한 날짜를 파라미터로 넘긴다.
 */
public final class Kst {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private Kst() {
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public static LocalTime nowTime() {
        return LocalTime.now(ZONE);
    }

    /** timestamptz → ISO 8601 (+09:00) 문자열 */
    public static String fmt(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(ZONE).toOffsetDateTime()
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    public static OffsetDateTime startOfMonth(LocalDate day) {
        return day.withDayOfMonth(1).atStartOfDay(ZONE).toOffsetDateTime();
    }
}
