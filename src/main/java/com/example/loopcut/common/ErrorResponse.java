package com.example.loopcut.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

public record ErrorResponse(Error error) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Error(String code, String message, Map<String, String> details) {
    }

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(new Error(code, message, null));
    }

    public static ErrorResponse of(String code, String message, Map<String, String> details) {
        return new ErrorResponse(new Error(code, message, details));
    }
}
