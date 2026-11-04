package com.nexus360.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus360.support.RepositoryTest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Rating aggregates and the derived helpful counters (legacy votes + ratings), and the one-rating-per-user guard. */
class KnowledgeRatingRepositoryTest extends RepositoryTest {

    @Autowired
    KbArticleRatingRepository ratings;

    @Autowired
    KnowledgeArticleRepository articles;

    @Autowired
    JdbcTemplate jdbc;

    private long user() {
        String email = "repo-" + UUID.randomUUID() + "@example.test";
        jdbc.update("insert into users (email, full_name, password_hash, role) values (?, 'Repo User', 'x', 'EMPLOYEE')", email);
        return jdbc.queryForObject("select id from users where email = ?", Long.class, email);
    }

    private long article() {
        String number = "KB-T" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into knowledge_articles (number, title, summary, content, article_type, category, status, "
                + "legacy_helpful_count, legacy_not_helpful_count, helpful_count, not_helpful_count, published_at) "
                + "values (?, 'Repository test article', 'Summary of the test', 'Content of the repository test', 'GUIDE', 'OTHER', "
                + "'PUBLISHED', 3, 1, 3, 1, now())", number);
        return jdbc.queryForObject("select id from knowledge_articles where number = ?", Long.class, number);
    }

    private KbArticleRating rating(long article, long user, int value) {
        KbArticleRating r = new KbArticleRating();
        r.setArticleId(article);
        r.setUserId(user);
        r.setRating(value);
        return r;
    }

    @Test
    void aggregatesFeedTheDerivedCounters() {
        long article = article();
        ratings.saveAndFlush(rating(article, user(), 5));
        ratings.saveAndFlush(rating(article, user(), 2));
        ratings.saveAndFlush(rating(article, user(), 4));

        Object[] row = ratings.aggregate(article).get(0);
        assertThat(((Number) row[0]).intValue()).isEqualTo(3);
        assertThat(((Number) row[1]).doubleValue()).isCloseTo(3.667, org.assertj.core.data.Offset.offset(0.001));
        assertThat(((Number) row[2]).intValue()).isEqualTo(2);

        articles.updateRatingStats(article, 3, new BigDecimal("3.67"), 2);
        KnowledgeArticle reloaded = articles.findById(article).orElseThrow();
        assertThat(reloaded.getRatingCount()).isEqualTo(3);
        assertThat(reloaded.getRatingAverage()).isEqualByComparingTo("3.67");
        assertThat(reloaded.getHelpfulCount()).isEqualTo(3 + 2); // legacy votes + ratings of 4-5
        assertThat(reloaded.getNotHelpfulCount()).isEqualTo(1 + 1); // legacy votes + ratings of 1-3
        assertThat(ratings.aggregate(article()).get(0)[0]).isEqualTo(0L);
    }

    @Test
    void aUserRatesAnArticleOnlyOnce() {
        long article = article();
        long user = user();
        ratings.saveAndFlush(rating(article, user, 3));
        assertThat(ratings.findByArticleIdAndUserId(article, user)).get().extracting(KbArticleRating::getRating).isEqualTo(3);
        assertThatThrownBy(() -> ratings.saveAndFlush(rating(article, user, 4))).isInstanceOf(DataIntegrityViolationException.class);
    }
}
