package com.gokhandegerli.personalvaultai.config;

import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.ai.chroma.vectorstore.ChromaVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;

@Configuration(proxyBeanMethods = false)
public class VectorStoreConfig {

    @Bean
    @Profile("simple")
    VectorStore simpleVectorStore(EmbeddingModel embeddingModel, AppProperties props) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        var file = props.vectorStore().simple().file();
        if (Files.isRegularFile(file)) {
            store.load(file.toFile());
        }
        return store;
    }

    @Bean
    @Profile("chroma")
    VectorStore chromaVectorStore(EmbeddingModel embeddingModel, RestClient.Builder restClientBuilder,
                                  AppProperties props) {
        var chroma = props.vectorStore().chroma();
        ChromaApi api = ChromaApi.builder()
                .baseUrl(chroma.url())
                .restClientBuilder(restClientBuilder)
                .build();
        return ChromaVectorStore.builder(api, embeddingModel)
                .collectionName(chroma.collection())
                .initializeSchema(true)
                .build();
    }

    @Bean
    @Profile("pgvector")
    VectorStore pgVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel, AppProperties props) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .initializeSchema(true)
                .dimensions(props.vectorStore().pgvector().dimensions())
                .build();
    }
}
