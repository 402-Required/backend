package com.example.loopcut.exports;

import com.example.loopcut.common.BusinessException;
import com.example.loopcut.common.Kst;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class ExportService {

    public record Preview(Map<String, Long> counts, String fileName, long estimatedBytes) {
    }

    public record FileData(byte[] content, String fileName, String contentType) {
    }

    private record Table(String title, List<String> headers, List<List<Object>> rows) {
    }

    private record Params(List<String> items, String format, LocalDate from, LocalDate to) {
    }

    private static final List<String> ITEM_ORDER = List.of("subscriptions", "history", "payments");
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final JdbcClient jdbc;

    public ExportService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Preview preview(long userId, String items, String format, String period, String from, String to) {
        Params p = parse(items, format, period, from, to);
        List<Table> tables = build(userId, p);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (int i = 0; i < tables.size(); i++) {
            counts.put(p.items().get(i), (long) tables.get(i).rows().size());
        }
        FileData file = render(tables, p.format());
        return new Preview(counts, file.fileName(), file.content().length);
    }

    public FileData download(long userId, String items, String format, String period, String from, String to) {
        Params p = parse(items, format, period, from, to);
        return render(build(userId, p), p.format());
    }

    // ------------------------------------------------------------------ 요청 해석

    private static Params parse(String items, String format, String period, String from, String to) {
        if (items == null || items.isBlank()) {
            throw BusinessException.validation("items", "subscriptions, history, payments 중 1개 이상 선택해 주세요.");
        }
        Set<String> requested = new java.util.HashSet<>();
        for (String raw : items.split(",")) {
            requested.add(raw.trim());
        }
        for (String i : requested) {
            if (!ITEM_ORDER.contains(i)) {
                throw BusinessException.validation("items", "subscriptions, history, payments 중에서 선택해 주세요.");
            }
        }
        List<String> ordered = ITEM_ORDER.stream().filter(requested::contains).toList();

        if ("pdf".equals(format)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "FORMAT_NOT_SUPPORTED", "PDF는 아직 지원하지 않아요.");
        }
        if (!"csv".equals(format) && !"xlsx".equals(format)) {
            throw BusinessException.validation("format", "csv 또는 xlsx여야 해요.");
        }

        LocalDate today = Kst.today();
        LocalDate periodFrom = null;
        LocalDate periodTo = null;
        switch (period) {
            case "all" -> { }
            case "1y" -> periodFrom = today.minusYears(1);
            case "6m" -> periodFrom = today.minusMonths(6);
            case "custom" -> {
                periodFrom = parseDate("from", from);
                periodTo = parseDate("to", to);
                if (periodFrom.isAfter(periodTo)) {
                    throw BusinessException.validation("from", "시작일은 종료일보다 앞이어야 해요.");
                }
            }
            default -> throw BusinessException.validation("period", "all, 1y, 6m, custom 중 하나여야 해요.");
        }
        return new Params(ordered, format, periodFrom, periodTo);
    }

    private static LocalDate parseDate(String field, String value) {
        if (value == null || value.isBlank()) {
            throw BusinessException.validation(field, "기간을 직접 지정할 때는 필수예요.");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw BusinessException.validation(field, "YYYY-MM-DD 형식이어야 해요.");
        }
    }

    // ------------------------------------------------------------------ 데이터

    private List<Table> build(long userId, Params p) {
        List<Table> tables = new ArrayList<>();
        for (String item : p.items()) {
            tables.add(switch (item) {
                case "subscriptions" -> subscriptions(userId);
                case "history" -> history(userId);
                default -> payments(userId, p);
            });
        }
        return tables;
    }

    private Table subscriptions(long userId) {
        List<List<Object>> rows = jdbc.sql("""
                        SELECT s.name, s.plan_name, c.name AS category, s.amount, s.billing_cycle,
                               s.first_billing_date, s.next_billing_date, s.notify_enabled, s.memo
                        FROM subscriptions s
                        JOIN categories c ON c.id = s.category_id
                        WHERE s.user_id = :uid AND s.status = 'active' AND s.deleted_at IS NULL
                        ORDER BY s.next_billing_date, s.id
                        """)
                .param("uid", userId)
                .query((rs, n) -> row(rs.getString("name"), rs.getString("plan_name"), rs.getString("category"),
                        rs.getInt("amount"), cycle(rs.getString("billing_cycle")),
                        rs.getObject("first_billing_date", LocalDate.class),
                        rs.getObject("next_billing_date", LocalDate.class),
                        rs.getBoolean("notify_enabled") ? "켜짐" : "꺼짐", rs.getString("memo")))
                .list();
        return new Table("현재 구독", List.of("서비스명", "요금제", "카테고리", "금액", "결제 주기", "첫 결제일", "다음 결제일",
                "알림", "메모"), rows);
    }

    private Table history(long userId) {
        List<List<Object>> rows = jdbc.sql("""
                        SELECT s.name, c.name AS category, s.amount, s.billing_cycle, MIN(p.billing_date) AS first_paid,
                               s.canceled_at, COUNT(p.id) AS paid_count, COALESCE(SUM(p.amount), 0) AS total_spent
                        FROM subscriptions s
                        JOIN categories c ON c.id = s.category_id
                        LEFT JOIN payments p ON p.subscription_id = s.id
                        WHERE s.user_id = :uid AND s.status = 'canceled' AND s.deleted_at IS NULL
                        GROUP BY s.id, c.id
                        ORDER BY s.canceled_at DESC, s.id DESC
                        """)
                .param("uid", userId)
                .query((rs, n) -> row(rs.getString("name"), rs.getString("category"), rs.getInt("amount"),
                        cycle(rs.getString("billing_cycle")), rs.getObject("first_paid", LocalDate.class),
                        rs.getObject("canceled_at", LocalDate.class), rs.getLong("paid_count"),
                        rs.getLong("total_spent")))
                .list();
        return new Table("지난 구독 이력", List.of("서비스명", "카테고리", "금액", "결제 주기", "첫 결제일", "해지일", "결제 횟수",
                "총 지출"), rows);
    }

    private Table payments(long userId, Params p) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.billing_date, s.name, c.name AS category, p.amount
                FROM payments p
                JOIN subscriptions s ON s.id = p.subscription_id
                JOIN categories c ON c.id = p.category_id
                WHERE p.user_id = :uid
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("uid", userId);
        if (p.from() != null) {
            sql.append(" AND p.billing_date >= :from");
            params.put("from", p.from());
        }
        if (p.to() != null) {
            sql.append(" AND p.billing_date <= :to");
            params.put("to", p.to());
        }
        sql.append(" ORDER BY p.billing_date DESC, p.id DESC");

        List<List<Object>> rows = jdbc.sql(sql.toString()).params(params)
                .query((rs, n) -> row(rs.getObject("billing_date", LocalDate.class), rs.getString("name"),
                        rs.getString("category"), rs.getInt("amount")))
                .list();
        return new Table("결제 내역", List.of("결제일", "서비스명", "카테고리", "금액"), rows);
    }

    private static List<Object> row(Object... cells) {
        List<Object> r = new ArrayList<>(cells.length);
        for (Object c : cells) {
            r.add(c);
        }
        return r;
    }

    private static String cycle(String billingCycle) {
        return "yearly".equals(billingCycle) ? "연 결제" : "월 결제";
    }

    // ------------------------------------------------------------------ 파일 생성

    private FileData render(List<Table> tables, String format) {
        String fileName = "loopcut_" + Kst.today() + "." + format;
        try {
            return "xlsx".equals(format)
                    ? new FileData(xlsx(tables), fileName,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    : new FileData(csv(tables), fileName, "text/csv; charset=UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException("내보내기 파일을 만들지 못했어요.", e);
        }
    }

    /** UTF-8 BOM 포함 (엑셀에서 한글이 깨지지 않게). 항목이 여러 개면 제목 행으로 구분한다. */
    private static byte[] csv(List<Table> tables) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tables.size(); i++) {
            Table t = tables.get(i);
            if (tables.size() > 1) {
                sb.append(csvCell("[" + t.title() + "]")).append("\r\n");
            }
            sb.append(String.join(",", t.headers().stream().map(ExportService::csvCell).toList())).append("\r\n");
            for (List<Object> r : t.rows()) {
                sb.append(String.join(",", r.stream().map(c -> csvCell(text(c))).toList())).append("\r\n");
            }
            if (i < tables.size() - 1) {
                sb.append("\r\n");
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(UTF8_BOM);
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static byte[] xlsx(List<Table> tables) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle header = wb.createCellStyle();
            header.setFont(bold);

            for (Table t : tables) {
                Sheet sheet = wb.createSheet(t.title());
                Row h = sheet.createRow(0);
                for (int c = 0; c < t.headers().size(); c++) {
                    Cell cell = h.createCell(c);
                    cell.setCellValue(t.headers().get(c));
                    cell.setCellStyle(header);
                    sheet.setColumnWidth(c, 18 * 256);
                }
                for (int r = 0; r < t.rows().size(); r++) {
                    Row row = sheet.createRow(r + 1);
                    List<Object> values = t.rows().get(r);
                    for (int c = 0; c < values.size(); c++) {
                        Object v = values.get(c);
                        if (v instanceof Number num) {
                            row.createCell(c).setCellValue(num.doubleValue());
                        } else if (v != null) {
                            row.createCell(c).setCellValue(safeText(text(v)));
                        }
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static String text(Object v) {
        return v == null ? "" : v.toString();
    }

    /** 수식으로 해석될 수 있는 문자열(= + - @)은 앞에 '를 붙여 엑셀 수식 주입을 막는다. */
    private static String safeText(String s) {
        return !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 ? "'" + s : s;
    }

    private static String csvCell(String value) {
        String s = safeText(value);
        boolean quote = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        return quote ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
