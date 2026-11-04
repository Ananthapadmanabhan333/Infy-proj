package com.nexus360.knowledge;

import com.nexus360.incident.Category;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Relevance search over the generated {@code search_vector} (title weighted A, summary/tags B, body C) using
 * {@code websearch_to_tsquery}, with trigram title similarity so short or misspelt queries still match.
 */
@Component
@RequiredArgsConstructor
public class KnowledgeSearch {

    public record Hit(long id, double score) {}

    public record Result(List<Hit> hits, long total) {}

    private final NamedParameterJdbcTemplate jdbc;

    public Result search(String query, KnowledgeArticle.ArticleType type, List<KnowledgeArticle.ArticleType> types, Category category,
                         Long serviceId, KnowledgeArticle.Status status, int offset, int limit) {
        // Exact intent first (all terms, websearch syntax); any single term and fuzzy title similarity rank lower,
        // so partial or misspelt queries ("vpn slw") still find articles.
        String orQuery = orQuery(query);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("q", query)
                .addValue("orq", orQuery.isBlank() ? "zzzznomatch" : orQuery)
                .addValue("limit", limit)
                .addValue("offset", offset);
        StringBuilder where = new StringBuilder(" where (a.search_vector @@ websearch_to_tsquery('english', :q) "
                + "or a.search_vector @@ to_tsquery('english', :orq) or similarity(a.title, :q) > 0.2)");
        if (type != null) {
            where.append(" and a.article_type = :type");
            params.addValue("type", type.name());
        } else if (types != null && !types.isEmpty()) {
            where.append(" and a.article_type in (:types)");
            params.addValue("types", types.stream().map(Enum::name).toList());
        }
        if (category != null) {
            where.append(" and a.category = :category");
            params.addValue("category", category.name());
        }
        if (serviceId != null) {
            where.append(" and a.service_id = :serviceId");
            params.addValue("serviceId", serviceId);
        }
        if (status != null) {
            where.append(" and a.status = :status");
            params.addValue("status", status.name());
        }
        String score = "(ts_rank_cd(a.search_vector, websearch_to_tsquery('english', :q), 32) "
                + "+ 0.3 * ts_rank_cd(a.search_vector, to_tsquery('english', :orq), 32) + 0.5 * similarity(a.title, :q))";
        List<Hit> hits = new ArrayList<>(jdbc.query(
                "select a.id, " + score + " as score from knowledge_articles a" + where
                        + " order by score desc, a.view_count desc limit :limit offset :offset",
                params, (rs, i) -> new Hit(rs.getLong("id"), rs.getDouble("score"))));
        Long total = jdbc.queryForObject("select count(*) from knowledge_articles a" + where, params, Long.class);
        return new Result(hits, total == null ? 0 : total);
    }

    /** Recommendations for an incident: published articles ranked by text relevance, boosted for the same service. */
    /** Terms OR-ed for to_tsquery. Only [a-z0-9] tokens are kept, so to_tsquery operator syntax cannot be injected. */
    static String orQuery(String text) {
        return java.util.Arrays.stream((text == null ? "" : text).toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+"))
                .filter(t -> t.length() > 2)
                .distinct()
                .limit(40)
                .collect(java.util.stream.Collectors.joining(" | "));
    }

    public List<Hit> recommend(String text, Long serviceId, int limit) {
        // Incident text is long, so OR the terms (plainto_tsquery would AND them and rarely match).
        String orQuery = orQuery(text);
        if (orQuery.isBlank()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("q", orQuery)
                .addValue("serviceId", serviceId == null ? -1L : serviceId)
                .addValue("limit", limit);
        return jdbc.query("select a.id, (ts_rank_cd(a.search_vector, q.query, 32) + case when a.service_id = :serviceId then 0.15 else 0 end) "
                        + "as score from knowledge_articles a, (select to_tsquery('english', :q) as query) q "
                        + "where a.status = 'PUBLISHED' and a.search_vector @@ q.query "
                        + "order by score desc, a.helpful_count desc limit :limit",
                params, (rs, i) -> new Hit(rs.getLong("id"), rs.getDouble("score")));
    }
}
