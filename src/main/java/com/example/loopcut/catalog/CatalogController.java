package com.example.loopcut.catalog;

import com.example.loopcut.catalog.CatalogService.Category;
import com.example.loopcut.catalog.CatalogService.SearchHistory;
import com.example.loopcut.catalog.CatalogService.ServiceItem;
import com.example.loopcut.common.ApiResponse;
import com.example.loopcut.common.AuthInterceptor;
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

@RestController
public class CatalogController {

    public record SearchRequest(
            @NotBlank(message = "검색어를 입력해 주세요.") @Size(max = 50, message = "검색어는 50자 이하로 입력해 주세요.") String keyword) {
    }

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/categories")
    public ApiResponse<List<Category>> categories() {
        return ApiResponse.of(catalog.categories());
    }

    @GetMapping("/services")
    public ApiResponse<List<ServiceItem>> services(@RequestParam(required = false) String q,
                                                   @RequestParam(required = false) Long categoryId,
                                                   @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.of(catalog.services(q, categoryId, Math.max(1, Math.min(limit, 50))));
    }

    @GetMapping("/services/{id}")
    public ApiResponse<ServiceItem> service(@PathVariable long id) {
        return ApiResponse.of(catalog.service(id));
    }

    @GetMapping("/search-histories")
    public ApiResponse<List<SearchHistory>> searchHistories(@RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        return ApiResponse.of(catalog.searchHistories(userId));
    }

    @PostMapping("/search-histories")
    public ResponseEntity<Void> saveSearch(@RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                           @Valid @RequestBody SearchRequest request) {
        catalog.saveSearch(userId, CatalogService.requireKeyword(request.keyword()));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/search-histories/{keyword}")
    public ResponseEntity<Void> deleteSearch(@RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                             @PathVariable String keyword) {
        catalog.deleteSearch(userId, keyword);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/search-histories")
    public ResponseEntity<Void> clearSearches(@RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        catalog.clearSearches(userId);
        return ResponseEntity.noContent().build();
    }
}
