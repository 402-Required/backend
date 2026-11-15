package com.example.loopcut.common;

import java.util.List;

/** 페이지네이션이 있는 목록 응답 ({ data: [...], meta: { nextCursor } }). */
public record ListResponse<T>(List<T> data, Meta meta) {

    public record Meta(String nextCursor) {
    }

    public static <T> ListResponse<T> of(List<T> data, String nextCursor) {
        return new ListResponse<>(data, new Meta(nextCursor));
    }
}
