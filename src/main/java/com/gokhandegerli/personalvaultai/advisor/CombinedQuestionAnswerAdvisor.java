package com.gokhandegerli.personalvaultai.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class CombinedQuestionAnswerAdvisor implements BaseAdvisor {

    private static final PromptTemplate CONTEXT_PROMPT_TEMPLATE = new PromptTemplate("""
            {query}

            Context information is below, surrounded by ---------------------

            ---------------------
            {question_answer_context}
            ---------------------

            Given the context and provided history information and not prior knowledge,
            reply to the user comment. If the answer is not in the context, inform
            the user that you can't answer the question.""");

    private static final int HISTORY_MESSAGES = 6;
    private static final int MIN_FULL_QUERY_LENGTH = 25;

    private final List<VectorStore> vectorStores;
    private final SearchRequest searchRequest;
    private final int order;
    private final ChatMemory chatMemory;

    public CombinedQuestionAnswerAdvisor(List<VectorStore> vectorStores, SearchRequest searchRequest, int order,
                                         ChatMemory chatMemory) {
        this.vectorStores = List.copyOf(vectorStores);
        this.searchRequest = searchRequest;
        this.order = order;
        this.chatMemory = chatMemory;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        String query = expandQuery(request);
        SearchRequest requestWithQuery = SearchRequest.from(searchRequest).query(query).build();

        List<Document> retrieved = new ArrayList<>();
        for (VectorStore store : vectorStores) {
            List<Document> docs = store.similaritySearch(requestWithQuery);
            retrieved.addAll(docs);
        }

        String contextText = retrieved.stream()
                .map(Document::getText)
                .reduce((a, b) -> a + "\n\n---\n\n" + b)
                .orElse("");

        String rendered = CONTEXT_PROMPT_TEMPLATE.render(Map.of(
                "query", query,
                "question_answer_context", contextText));
        Prompt augmentedPrompt = request.prompt().augmentUserMessage(rendered);

        Map<String, Object> context = new java.util.HashMap<>(request.context());
        context.put(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, retrieved);

        return request.mutate()
                .prompt(augmentedPrompt)
                .context(context)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
        return response;
    }

    @Override
    public int getOrder() {
        return order;
    }

    private String expandQuery(ChatClientRequest request) {
        String current = request.prompt().getUserMessage() != null
                ? request.prompt().getUserMessage().getText()
                : "";

        if (current.length() >= MIN_FULL_QUERY_LENGTH) {
            return current;
        }

        Object conversationId = request.context().get(ChatMemory.CONVERSATION_ID);
        if (conversationId == null) {
            return current;
        }

        List<Message> history = chatMemory.get(String.valueOf(conversationId));
        if (history.size() > HISTORY_MESSAGES) {
            history = history.subList(history.size() - HISTORY_MESSAGES, history.size());
        }

        String historyText = history.stream()
                .filter(m -> m instanceof UserMessage)
                .map(Message::getText)
                .filter(Objects::nonNull)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        return historyText.isBlank() ? current : current + "\n\nPrevious conversation:\n" + historyText;
    }
}
