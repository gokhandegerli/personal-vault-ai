package com.gokhandegerli.personalvaultai.service;

import org.springframework.ai.document.Document;

import java.util.List;

@FunctionalInterface
public interface HybridRetriever {

    List<Document> search(String query, int topK);
}
