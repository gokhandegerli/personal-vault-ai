package com.gokhandegerli.personalvaultai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("pgvector")
public class PgVectorSearchIndexInitializer implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(PgVectorSearchIndexInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public PgVectorSearchIndexInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.execute("""
                    ALTER TABLE vector_store
                    ADD COLUMN IF NOT EXISTS search_vector tsvector
                    GENERATED ALWAYS AS (to_tsvector('turkish', coalesce(content, ''))) STORED
                    """);
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS vector_store_search_vector_idx"
                    + " ON vector_store USING GIN (search_vector)");
            logger.info("Hybrid search FTS column and GIN index ready on vector_store");
        } catch (Exception e) {
            logger.warn("Failed to prepare hybrid search index: {}", e.getMessage());
        }
    }
}
