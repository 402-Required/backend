package com.example.loopcut.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 부분 수정(PATCH)용 UPDATE 문 조립. 컬럼명은 코드에 박힌 상수만 넘길 것. */
public final class UpdateBuilder {

    private final String table;
    private final List<String> sets = new ArrayList<>();
    private final Map<String, Object> params = new HashMap<>();

    public UpdateBuilder(String table) {
        this.table = table;
    }

    public UpdateBuilder set(String column, Object value) {
        sets.add(column + " = :" + column);
        params.put(column, value);
        return this;
    }

    /** 예: setCast("billing_cycle", "monthly", "billing_cycle") */
    public UpdateBuilder setCast(String column, Object value, String sqlType) {
        sets.add(column + " = CAST(:" + column + " AS " + sqlType + ")");
        params.put(column, value);
        return this;
    }

    public UpdateBuilder setRaw(String column, String expression) {
        sets.add(column + " = " + expression);
        return this;
    }

    public UpdateBuilder param(String name, Object value) {
        params.put(name, value);
        return this;
    }

    public boolean isEmpty() {
        return sets.isEmpty();
    }

    public int execute(JdbcClient jdbc, String where) {
        String sql = "UPDATE " + table + " SET " + String.join(", ", sets) + " WHERE " + where;
        return jdbc.sql(sql).params(params).update();
    }
}
