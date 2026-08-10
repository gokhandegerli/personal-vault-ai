package com.gokhandegerli.personalvaultai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Profile("pgvector")
public class HybridSearchService implements HybridRetriever {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);

    private static final String TABLE = "vector_store";
    private static final String FTS_CONFIG = "turkish";
    private static final int CANDIDATES = 20;
    private static final int RRF_K = 60;

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;

    public HybridSearchService(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public List<Document> search(String query, int topK) {
        float[] embedding = embeddingModel.embed(query);
        String vectorLiteral = toVectorLiteral(embedding);

        List<Hit> vector = jdbcTemplate.query(
                "SELECT id::text AS id, content, metadata FROM " + TABLE
                        + " ORDER BY embedding <=> ?::vector LIMIT ?",
                this::mapHit, vectorLiteral, CANDIDATES);

        List<Hit> fts = List.of();
        try {
            fts = jdbcTemplate.query(
                    "SELECT id::text AS id, content, metadata FROM " + TABLE
                            + " , LATERAL ("
                            + "   SELECT to_tsquery('" + FTS_CONFIG + "', string_agg('\"' || lexeme || '\"', ' | ')) AS q"
                            + "   FROM unnest(to_tsvector('" + FTS_CONFIG + "', ?))"
                            + "   WHERE lexeme !~ '^[0-9]+$'"
                            + " ) t"
                            + " WHERE search_vector @@ q"
                            + " ORDER BY ts_rank_cd(search_vector, q) DESC"
                            + " LIMIT ?",
                    this::mapHit, query, CANDIDATES);
        } catch (Exception e) {
            logger.warn("Full-text search failed, using vector search only: {}", e.getMessage());
        }

        return fuse(vector, fts, topK);
    }

    private Hit mapHit(ResultSet rs, int rowNum) throws SQLException {
        return new Hit(rs.getString("id"), rs.getString("content"), rs.getString("metadata"));
    }

    private List<Document> fuse(List<Hit> vector, List<Hit> fts, int topK) {
        Map<String, Hit> hits = new HashMap<>();
        Map<String, Double> scores = new HashMap<>();
        addRanked(vector, hits, scores);
        addRanked(fts, hits, scores);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> hits.get(entry.getKey()).toDocument(objectMapper))
                .toList();
    }

    private void addRanked(List<Hit> docs, Map<String, Hit> hits, Map<String, Double> scores) {
        for (int i = 0; i < docs.size(); i++) {
            Hit hit = docs.get(i);
            hits.putIfAbsent(hit.id(), hit);
            scores.merge(hit.id(), 1.0 / (RRF_K + i + 1), Double::sum);
        }
    }

    private static String toVectorLiteral(float[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Float.toString(values[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    private record Hit(String id, String content, String metadata) {

        Document toDocument(ObjectMapper objectMapper) {
            return Document.builder()
                    .id(id)
                    .text(content)
                    .metadata(parseMetadata(objectMapper, metadata))
                    .build();
        }
    }

    private static Map<String, Object> parseMetadata(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
