package com.gokhandegerli.personalvaultai.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;

public class HybridSearchVectorStore implements VectorStore {

    private final VectorStore delegate;
    private final HybridRetriever hybrid;

    public HybridSearchVectorStore(VectorStore delegate, HybridRetriever hybrid) {
        this.delegate = delegate;
        this.hybrid = hybrid;
    }

    @Override
    public void add(List<Document> documents) {
        delegate.add(documents);
    }

    @Override
    public void delete(List<String> ids) {
        delegate.delete(ids);
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        delegate.delete(filterExpression);
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        if (request.hasFilterExpression()) {
            return delegate.similaritySearch(request);
        }
        return hybrid.search(request.getQuery(), request.getTopK());
    }
}
