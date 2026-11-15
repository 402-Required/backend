package com.example.loopcut.catalog;

import com.example.loopcut.common.BusinessException;
import com.example.loopcut.common.Db;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class CatalogService {

    public record CategoryRef(String id, String code, String name) {
    }

    public record Category(String id, String code, String name, String description) {
    }

    public record Plan(String id, String name, int price, String billingCycle) {
    }

    public record ServiceItem(String id, String name, String logoUrl, String brandColor, CategoryRef category,
                              List<Plan> plans) {
    }

    public record SearchHistory(String keyword, String searchedAt) {
    }

    private static final String SERVICE_SELECT = """
            SELECT s.id, s.name, s.logo_url, s.brand_color, c.id AS category_id, c.code AS category_code,
                   c.name AS category_name
            FROM services s JOIN categories c ON c.id = s.category_id
            """;

    private final JdbcClient jdbc;

    public CatalogService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Category> categories() {
        return jdbc.sql("SELECT id, code, name, description FROM categories ORDER BY sort_order, id")
                .query((rs, n) -> new Category(Db.id(rs.getLong("id")), rs.getString("code"), rs.getString("name"),
                        rs.getString("description")))
                .list();
    }

    public List<ServiceItem> services(String q, Long categoryId, int limit) {
        StringBuilder sql = new StringBuilder(SERVICE_SELECT).append(" WHERE s.is_active");
        Map<String, Object> params = new HashMap<>();
        if (categoryId != null) {
            sql.append(" AND s.category_id = :categoryId");
            params.put("categoryId", categoryId);
        }

        String keyword = q == null ? "" : q.trim();
        if (keyword.isEmpty()) {
            sql.append(" ORDER BY s.popularity DESC, s.id");
        } else {
            params.put("q", keyword);
            params.put("contains", "%" + Db.escapeLike(keyword) + "%");
            params.put("prefix", Db.escapeLike(keyword) + "%");
            sql.append("""
                     AND (s.name ILIKE :contains
                          OR EXISTS (SELECT 1 FROM unnest(s.search_keywords) k WHERE k ILIKE :contains)
                          OR s.name % :q)
                     ORDER BY CASE WHEN lower(s.name) = lower(:q) THEN 0
                                   WHEN s.name ILIKE :prefix THEN 1
                                   WHEN EXISTS (SELECT 1 FROM unnest(s.search_keywords) k WHERE k ILIKE :prefix) THEN 1
                                   ELSE 2 END,
                              similarity(s.name, :q) DESC, s.popularity DESC, s.id
                    """);
        }
        sql.append(" LIMIT :limit");
        params.put("limit", limit);

        return attachPlans(jdbc.sql(sql.toString()).params(params).query(CatalogService::mapService).list());
    }

    public ServiceItem service(long id) {
        List<ServiceItem> found = attachPlans(jdbc.sql(SERVICE_SELECT + " WHERE s.id = :id AND s.is_active")
                .param("id", id).query(CatalogService::mapService).list());
        if (found.isEmpty()) {
            throw BusinessException.notFound("SERVICE_NOT_FOUND", "서비스를 찾을 수 없어요.");
        }
        return found.get(0);
    }

    // ---------------------------------------------------------------- 최근 검색어

    public List<SearchHistory> searchHistories(long userId) {
        return jdbc.sql("SELECT keyword, searched_at FROM search_histories WHERE user_id = :uid ORDER BY searched_at DESC LIMIT 10")
                .param("uid", userId)
                .query((rs, n) -> new SearchHistory(rs.getString("keyword"), Db.tsKst(rs, "searched_at")))
                .list();
    }

    public void saveSearch(long userId, String keyword) {
        jdbc.sql("""
                        INSERT INTO search_histories (user_id, keyword) VALUES (:uid, :kw)
                        ON CONFLICT (user_id, keyword) DO UPDATE SET searched_at = now()
                        """)
                .param("uid", userId).param("kw", keyword).update();
    }

    public void deleteSearch(long userId, String keyword) {
        jdbc.sql("DELETE FROM search_histories WHERE user_id = :uid AND keyword = :kw")
                .param("uid", userId).param("kw", keyword).update();
    }

    public void clearSearches(long userId) {
        jdbc.sql("DELETE FROM search_histories WHERE user_id = :uid").param("uid", userId).update();
    }

    // ---------------------------------------------------------------- 내부

    private static ServiceItem mapService(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new ServiceItem(Db.id(rs.getLong("id")), rs.getString("name"), rs.getString("logo_url"),
                rs.getString("brand_color"),
                new CategoryRef(Db.id(rs.getLong("category_id")), rs.getString("category_code"),
                        rs.getString("category_name")),
                new ArrayList<>());
    }

    private List<ServiceItem> attachPlans(List<ServiceItem> items) {
        if (items.isEmpty()) {
            return items;
        }
        Map<String, ServiceItem> byId = new LinkedHashMap<>();
        items.forEach(i -> byId.put(i.id(), i));
        List<Long> ids = items.stream().map(i -> Long.parseLong(i.id())).toList();

        jdbc.sql("""
                        SELECT id, service_id, name, price, billing_cycle FROM service_plans
                        WHERE service_id IN (:ids) ORDER BY billing_cycle, price, id
                        """)
                .param("ids", ids)
                .query((rs, n) -> {
                    byId.get(Db.id(rs.getLong("service_id"))).plans().add(
                            new Plan(Db.id(rs.getLong("id")), rs.getString("name"), rs.getInt("price"),
                                    rs.getString("billing_cycle")));
                    return null;
                })
                .list();
        return items;
    }

    static String requireKeyword(String keyword) {
        String k = keyword == null ? "" : keyword.trim();
        if (k.isEmpty() || k.length() > 50) {
            throw BusinessException.validation("keyword", "검색어는 1~50자로 입력해 주세요.");
        }
        return k;
    }
}
