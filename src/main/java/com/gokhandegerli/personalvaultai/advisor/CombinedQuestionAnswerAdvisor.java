package com.gokhandegerli.personalvaultai.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    private final List<VectorStore> vectorStores;
    private final SearchRequest searchRequest;
    private final int order;

    public CombinedQuestionAnswerAdvisor(List<VectorStore> vectorStores, SearchRequest searchRequest, int order) {
        this.vectorStores = List.copyOf(vectorStores);
        this.searchRequest = searchRequest;
        this.order = order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        String query = request.prompt().getUserMessage() != null
                ? request.prompt().getUserMessage().getText()
                : "";
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
}
