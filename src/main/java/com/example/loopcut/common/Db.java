package com.example.loopcut.common;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** ResultSet 읽기 / 직렬화 보조 */
public final class Db {

    private Db() {
    }

    /** bigint ID는 JSON에서 문자열로 내려준다 (정밀도 문제 방지) */
    public static String id(Long value) {
        return value == null ? null : value.toString();
    }

    public static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    public static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    public static LocalDate date(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, LocalDate.class);
    }

    public static OffsetDateTime ts(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, OffsetDateTime.class);
    }

    public static String tsKst(ResultSet rs, String col) throws SQLException {
        return Kst.fmt(ts(rs, col));
    }

    /** smallint[] / int[] 컬럼 → List (NULL이면 null) */
    public static List<Integer> intList(ResultSet rs, String col) throws SQLException {
        Array a = rs.getArray(col);
        if (a == null) {
            return null;
        }
        Object[] values = (Object[]) a.getArray();
        List<Integer> out = new ArrayList<>(values.length);
        for (Object v : values) {
            out.add(((Number) v).intValue());
        }
        return out;
    }

    /**
     * smallint[] 리터럴. 호출 전에 허용값(0,1,3,7) 검증을 마친 정수만 넘겨야 한다.
     * 배열 바인딩 대신 리터럴을 쓰는 이유: 드라이버/파라미터 확장 동작에 기대지 않기 위해서.
     */
    public static String smallintArray(List<Integer> values) {
        if (values == null) {
            return "CAST(NULL AS smallint[])";
        }
        String body = values.stream().map(v -> Integer.toString(v)).collect(Collectors.joining(","));
        return "CAST(ARRAY[" + body + "]::int[] AS smallint[])";
    }

    /** LIKE 패턴용 이스케이프 (\ % _) */
    public static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
