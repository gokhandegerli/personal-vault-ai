package com.gokhandegerli.personalvaultai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Rag rag,
        VectorStore vectorStore) {

    public record Rag(
            Path rootPath,
            int topK,
            int chunkSize,
            List<String> excludedDirs,
            int maxFiles) {
    }

    public record VectorStore(
            Simple simple,
            Chroma chroma,
            Pgvector pgvector) {

        public record Simple(Path file) {
        }

        public record Chroma(String url, String collection) {
        }

        public record Pgvector(int dimensions) {
        }
    }
}
