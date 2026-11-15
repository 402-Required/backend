package com.example.loopcut.support;

import com.example.loopcut.common.ApiResponse;
import com.example.loopcut.common.AuthInterceptor;
import com.example.loopcut.common.Db;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 도움말(FAQ) / 1:1 문의 */
@RestController
public class SupportController {

    public record Faq(String id, String question, String answer) {
    }

    public record InquiryRequest(
            @NotBlank(message = "제목을 입력해 주세요.") @Size(max = 100, message = "제목은 100자 이하로 입력해 주세요.") String title,
            @NotBlank(message = "내용을 입력해 주세요.") @Size(max = 2000, message = "내용은 2000자 이하로 입력해 주세요.") String content) {
    }

    public record Inquiry(String id, String title, String content, String status, String answer, String answeredAt,
                          String createdAt) {
    }

    private static final String INQUIRY_SELECT =
            "SELECT id, title, content, status, answer, answered_at, created_at FROM inquiries WHERE user_id = :uid";

    private final JdbcClient jdbc;

    public SupportController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/faqs")
    public ApiResponse<List<Faq>> faqs(@RequestParam(required = false) String q) {
        String keyword = q == null ? "" : q.trim();
        var query = keyword.isEmpty()
                ? jdbc.sql("SELECT id, question, answer FROM faqs WHERE is_published ORDER BY sort_order, id")
                : jdbc.sql("""
                                SELECT id, question, answer FROM faqs
                                WHERE is_published AND (question ILIKE :like OR answer ILIKE :like)
                                ORDER BY sort_order, id
                                """)
                        .param("like", "%" + Db.escapeLike(keyword) + "%");
        return ApiResponse.of(query.query((rs, n) -> new Faq(Db.id((long) rs.getInt("id")),
                rs.getString("question"), rs.getString("answer"))).list());
    }

    @PostMapping("/inquiries")
    public ResponseEntity<ApiResponse<Inquiry>> createInquiry(@RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                                              @Valid @RequestBody InquiryRequest request) {
        long id = jdbc.sql("INSERT INTO inquiries (user_id, title, content) VALUES (:uid, :title, :content) RETURNING id")
                .param("uid", userId).param("title", request.title().trim()).param("content", request.content().trim())
                .query(Long.class).single();
        Inquiry created = jdbc.sql(INQUIRY_SELECT + " AND id = :id").param("uid", userId).param("id", id)
                .query(SupportController::mapInquiry).single();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(created));
    }

    @GetMapping("/inquiries")
    public ApiResponse<List<Inquiry>> inquiries(@RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        return ApiResponse.of(jdbc.sql(INQUIRY_SELECT + " ORDER BY created_at DESC, id DESC")
                .param("uid", userId).query(SupportController::mapInquiry).list());
    }

    private static Inquiry mapInquiry(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Inquiry(Db.id(rs.getLong("id")), rs.getString("title"), rs.getString("content"),
                rs.getString("status"), rs.getString("answer"), Db.tsKst(rs, "answered_at"),
                Db.tsKst(rs, "created_at"));
    }
}
