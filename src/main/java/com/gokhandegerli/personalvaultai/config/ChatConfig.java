package com.gokhandegerli.personalvaultai.config;

import com.gokhandegerli.personalvaultai.advisor.CombinedQuestionAnswerAdvisor;
import com.gokhandegerli.personalvaultai.service.CorrectionService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ChatConfig {

    private static final String SYSTEM_PROMPT = """
            You are an assistant that answers questions using ONLY the provided
            context, which comes from a personal knowledge vault (Obsidian notes
            about Java, Spring, system design, projects and infrastructure).

            Rules:
            - Answer based only on the retrieved context.
            - If the context does not contain the answer, say so explicitly.
            - Be concise and factual.
            """;

    @Bean
    ChatClient chatClient(ChatClient.Builder builder, VectorStore vectorStore,
                          CorrectionService correctionService, AppProperties props) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(new CombinedQuestionAnswerAdvisor(
                        java.util.List.of(vectorStore, correctionService.store()),
                        SearchRequest.builder()
                                .topK(props.rag().topK())
                                .build(),
                        0))
                .build();
    }
}
