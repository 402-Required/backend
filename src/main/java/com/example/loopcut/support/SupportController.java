package com.example.loopcut.support;

import com.example.loopcut.common.ApiResponse;
import com.example.loopcut.common.AuthInterceptor;
import com.example.loopcut.common.Db;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Tag(name = "도움말", description = "자주 묻는 질문(FAQ), 1:1 문의")
@RestController
public class SupportController {

    public record Faq(
            @Schema(description = "FAQ ID (문자열)", example = "1") String id,
            @Schema(description = "질문", example = "구독은 어떻게 등록하나요?") String question,
            @Schema(description = "답변") String answer) {
    }

    public record InquiryRequest(
            @Schema(description = "제목 (1~100자)", example = "알림이 안 와요")
            @NotBlank(message = "제목을 입력해 주세요.") @Size(max = 100, message = "제목은 100자 이하로 입력해 주세요.") String title,
            @Schema(description = "내용 (1~2000자)", example = "결제일 알림이 오지 않습니다. 확인 부탁드려요.")
            @NotBlank(message = "내용을 입력해 주세요.") @Size(max = 2000, message = "내용은 2000자 이하로 입력해 주세요.") String content) {
    }

    public record Inquiry(
            @Schema(description = "문의 ID (문자열)", example = "3") String id,
            String title,
            String content,
            @Schema(description = "pending=답변 대기, answered=답변 완료", allowableValues = {"pending", "answered"}, example = "pending") String status,
            @Schema(description = "답변 내용. 아직 없으면 null", nullable = true) String answer,
            @Schema(description = "답변 시각 (한국 시간). 아직 없으면 null", nullable = true) String answeredAt,
            @Schema(description = "문의 등록 시각 (한국 시간)") String createdAt) {
    }

    private static final String INQUIRY_SELECT =
            "SELECT id, title, content, status, answer, answered_at, created_at FROM inquiries WHERE user_id = :uid";

    private final JdbcClient jdbc;

    public SupportController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Operation(summary = "자주 묻는 질문 목록 / 검색",
            description = """
                    **화면: 마이 > 도움말 (FAQ 목록, 검색창)**

                    노출 중인 FAQ를 정해진 순서대로 내려줘요. `q`를 주면 질문과 답변에서 검색해요.
                    """)
    @SecurityRequirements
    @GetMapping("/faqs")
    public ApiResponse<List<Faq>> faqs(
            @Parameter(description = "검색어 (질문·답변에서 찾음). 비우면 전체", example = "알림") @RequestParam(required = false) String q) {
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

    @Operation(summary = "1:1 문의 등록",
            description = """
                    **화면: 마이 > 도움말 > 1:1 문의 작성**

                    등록하면 `status`가 `pending`(답변 대기)으로 만들어져요.
                    """)
    @PostMapping("/inquiries")
    public ResponseEntity<ApiResponse<Inquiry>> createInquiry(
            @Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                                              @Valid @RequestBody InquiryRequest request) {
        long id = jdbc.sql("INSERT INTO inquiries (user_id, title, content) VALUES (:uid, :title, :content) RETURNING id")
                .param("uid", userId).param("title", request.title().trim()).param("content", request.content().trim())
                .query(Long.class).single();
        Inquiry created = jdbc.sql(INQUIRY_SELECT + " AND id = :id").param("uid", userId).param("id", id)
                .query(SupportController::mapInquiry).single();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(created));
    }

    @Operation(summary = "내 문의 목록",
            description = """
                    **화면: 마이 > 도움말 > 내 문의 내역**

                    내가 남긴 문의만 최신순으로 내려가요. 답변이 달렸으면 `status`가 `answered`이고 `answer`에 내용이 있어요.
                    """)
    @GetMapping("/inquiries")
    public ApiResponse<List<Inquiry>> inquiries(
            @Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        return ApiResponse.of(jdbc.sql(INQUIRY_SELECT + " ORDER BY created_at DESC, id DESC")
                .param("uid", userId).query(SupportController::mapInquiry).list());
    }

    private static Inquiry mapInquiry(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Inquiry(Db.id(rs.getLong("id")), rs.getString("title"), rs.getString("content"),
                rs.getString("status"), rs.getString("answer"), Db.tsKst(rs, "answered_at"),
                Db.tsKst(rs, "created_at"));
    }
}
