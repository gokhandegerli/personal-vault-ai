package com.gokhandegerli.personalvaultai.service;

import com.gokhandegerli.personalvaultai.dto.ChatResponse;
import com.gokhandegerli.personalvaultai.dto.Source;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
public class ChatService {

    private static final int EXCERPT_LENGTH = 300;
    private static final String DEFAULT_CONVERSATION_ID = "default";

    private final ChatClient chatClient;

    public ChatService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public ChatResponse ask(String message, String sessionId) {
        String conversationId = conversationId(sessionId);
        ChatClientResponse response = chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .chatClientResponse();

        String answer = response.chatResponse().getResult().getOutput().getText();

        @SuppressWarnings("unchecked")
        List<Document> documents = (List<Document>) response.context()
                .get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);

        List<Source> sources = documents == null
                ? List.of()
                : documents.stream()
                        .map(d -> new Source(
                                String.valueOf(d.getMetadata().getOrDefault("source", "unknown")),
                                excerpt(d.getText())))
                        .toList();

        return new ChatResponse(answer, sources);
    }

    public Flux<String> stream(String message) {
        return chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
                .stream()
                .content();
    }

    private String excerpt(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= EXCERPT_LENGTH
                ? text
                : text.substring(0, EXCERPT_LENGTH) + "...";
    }
}
