package com.example.loopcut.common;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * PATCH 요청처럼 "키가 없음"과 "null로 보냄"을 구분해야 하는 본문을 검증하며 읽는다.
 * 검증 실패는 모아 두었다가 {@link #check()}에서 한 번에 VALIDATION_ERROR로 던진다.
 */
public final class Body {

    private final Map<String, Object> map;
    private final Map<String, String> errors = new LinkedHashMap<>();

    private Body(Map<String, Object> map) {
        this.map = map == null ? Map.of() : map;
    }

    public static Body of(Map<String, Object> map) {
        return new Body(map);
    }

    public boolean has(String key) {
        return map.containsKey(key);
    }

    public boolean isNull(String key) {
        return map.get(key) == null;
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    /** 키가 없거나 null이면 오류로 기록 */
    public void require(String key) {
        if (isNull(key)) {
            errors.putIfAbsent(key, "필수 값이에요.");
        }
    }

    public String string(String key, int min, int max) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof String s)) {
            errors.put(key, "문자열이어야 해요.");
            return null;
        }
        String t = s.trim();
        if (t.length() < min || t.length() > max) {
            errors.put(key, min == 0 ? max + "자 이하로 입력해 주세요." : min + "~" + max + "자로 입력해 주세요.");
            return null;
        }
        return t;
    }

    public Integer integer(String key, long min, long max) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        Long n = toLong(v);
        if (n == null || n < min || n > max) {
            errors.put(key, min + " 이상 " + max + " 이하의 정수여야 해요.");
            return null;
        }
        return n.intValue();
    }

    /** ID (숫자 또는 숫자 문자열) */
    public Long id(String key) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        Long n = toLong(v);
        if (n == null || n <= 0) {
            errors.put(key, "올바른 ID가 아니에요.");
            return null;
        }
        return n;
    }

    public Boolean bool(String key) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        errors.put(key, "true 또는 false여야 해요.");
        return null;
    }

    public LocalDate date(String key) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        try {
            return LocalDate.parse(v.toString());
        } catch (DateTimeParseException e) {
            errors.put(key, "YYYY-MM-DD 형식이어야 해요.");
            return null;
        }
    }

    public String oneOf(String key, String... allowed) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        for (String a : allowed) {
            if (a.equals(v.toString())) {
                return a;
            }
        }
        errors.put(key, String.join(", ", allowed) + " 중 하나여야 해요.");
        return null;
    }

    /** 허용값만 담긴 배열 → 중복 제거 + 오름차순 정렬. null이면 null. */
    public List<Integer> intSet(String key, Set<Integer> allowed) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        if (!(v instanceof Collection<?> c)) {
            errors.put(key, "배열이어야 해요.");
            return null;
        }
        return normalizeDays(key, c, allowed, errors);
    }

    public void error(String key, String reason) {
        errors.put(key, reason);
    }

    public void check() {
        if (!errors.isEmpty()) {
            throw BusinessException.validation(new LinkedHashMap<>(errors));
        }
    }

    /** 알림 시점 배열(0,1,3,7) 검증 + 정규화. 오류가 있으면 errors에 기록하고 null 반환. */
    public static List<Integer> normalizeDays(String field, Collection<?> raw, Set<Integer> allowed,
                                              Map<String, String> errors) {
        TreeSet<Integer> out = new TreeSet<>();
        for (Object o : raw) {
            Long n = toLong(o);
            if (n == null || !allowed.contains(n.intValue())) {
                errors.put(field, allowed + " 중 값만 쓸 수 있어요.");
                return null;
            }
            out.add(n.intValue());
        }
        return new ArrayList<>(out);
    }

    private static Long toLong(Object v) {
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) ? n.longValue() : null;
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
