package com.example.loopcut.catalog;

import com.example.loopcut.catalog.CatalogService.Category;
import com.example.loopcut.catalog.CatalogService.SearchHistory;
import com.example.loopcut.catalog.CatalogService.ServiceItem;
import com.example.loopcut.common.ApiResponse;
import com.example.loopcut.common.AuthInterceptor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "구독 추가 (카탈로그)", description = "서비스 검색, 카테고리, 최근 검색어")
@RestController
public class CatalogController {

    public record SearchRequest(
            @Schema(description = "검색어 (1~50자)", example = "넷플")
            @NotBlank(message = "검색어를 입력해 주세요.") @Size(max = 50, message = "검색어는 50자 이하로 입력해 주세요.") String keyword) {
    }

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @Operation(summary = "카테고리 목록",
            description = """
                    **화면: 구독 추가(카테고리 선택), 내 구독(카테고리 탭)**

                    `sort_order` 순서로 내려와요. 구독 등록 때는 여기서 받은 `id`를 `categoryId`로, 내 구독 목록 필터에는 `code`를 `categoryCode`로 써요.
                    """)
    @SecurityRequirements
    @GetMapping("/categories")
    public ApiResponse<List<Category>> categories() {
        return ApiResponse.of(catalog.categories());
    }

    @Operation(summary = "서비스 검색 / 인기 서비스 / 카테고리별 서비스",
            description = """
                    **화면: 구독 추가 (인기 서비스, 검색 결과, 카테고리별 목록)**

                    - `q` 없음: 인기순 (**인기 서비스 영역**)
                    - `q` 있음: 이름·검색 키워드에서 찾고, 정확히 일치 → 앞부분 일치 → 비슷한 순서. 예: "넷플" → Netflix
                    - 각 서비스의 `plans`(요금제)를 고르면 금액과 결제 주기를 자동으로 채울 수 있어요.
                    """)
    @SecurityRequirements
    @GetMapping("/services")
    public ApiResponse<List<ServiceItem>> services(
            @Parameter(description = "검색어. 비우면 인기순", example = "넷플") @RequestParam(required = false) String q,
            @Parameter(description = "카테고리 ID로 필터 (GET /categories의 id)") @RequestParam(required = false) Long categoryId,
            @Parameter(description = "가져올 개수 (1~50, 기본 20)") @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.of(catalog.services(q, categoryId, Math.max(1, Math.min(limit, 50))));
    }

    @Operation(summary = "서비스 상세 (요금제 포함)",
            description = """
                    **화면: 구독 추가 (서비스를 선택한 뒤 요금제 선택)**

                    에러: `404 SERVICE_NOT_FOUND`
                    """)
    @SecurityRequirements
    @GetMapping("/services/{id}")
    public ApiResponse<ServiceItem> service(@Parameter(description = "서비스 ID") @PathVariable long id) {
        return ApiResponse.of(catalog.service(id));
    }

    @Operation(summary = "최근 검색어 목록",
            description = """
                    **화면: 구독 추가 (검색창을 눌렀을 때 최근 검색어)**

                    최근 10개, 가장 최근에 검색한 것부터 내려와요.
                    """)
    @GetMapping("/search-histories")
    public ApiResponse<List<SearchHistory>> searchHistories(
            @Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        return ApiResponse.of(catalog.searchHistories(userId));
    }

    @Operation(summary = "최근 검색어 저장",
            description = """
                    **화면: 구독 추가 (사용자가 검색을 실행했을 때)**

                    같은 검색어를 다시 저장하면 중복으로 쌓이지 않고 최근 시각만 갱신돼요. 성공하면 `204`.
                    """)
    @PostMapping("/search-histories")
    public ResponseEntity<Void> saveSearch(@Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                           @Valid @RequestBody SearchRequest request) {
        catalog.saveSearch(userId, CatalogService.requireKeyword(request.keyword()));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "최근 검색어 하나 삭제",
            description = """
                    **화면: 구독 추가 (최근 검색어 옆 X 버튼)**

                    검색어는 URL 인코딩해서 보내세요. 성공하면 `204`.
                    """)
    @DeleteMapping("/search-histories/{keyword}")
    public ResponseEntity<Void> deleteSearch(@Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                             @Parameter(description = "삭제할 검색어 (URL 인코딩)") @PathVariable String keyword) {
        catalog.deleteSearch(userId, keyword);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "최근 검색어 전체 삭제",
            description = """
                    **화면: 구독 추가 (최근 검색어 "전체 삭제")**

                    성공하면 `204`.
                    """)
    @DeleteMapping("/search-histories")
    public ResponseEntity<Void> clearSearches(@Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        catalog.clearSearches(userId);
        return ResponseEntity.noContent().build();
    }
}
