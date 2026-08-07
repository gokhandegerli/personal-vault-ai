package com.gokhandegerli.personalvaultai.service;

import com.gokhandegerli.personalvaultai.config.AppProperties;
import com.gokhandegerli.personalvaultai.dto.FeedbackRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
public class CorrectionService {

    private static final Logger logger = LoggerFactory.getLogger(CorrectionService.class);

    private final SimpleVectorStore correctionStore;
    private final Path correctionsFile;
    private final Path storeFile;

    public CorrectionService(EmbeddingModel embeddingModel, AppProperties props) {
        this.correctionStore = SimpleVectorStore.builder(embeddingModel).build();
        this.correctionsFile = Path.of(props.correction().file());
        this.storeFile = Path.of(props.correction().storeFile());
        load();
    }

    public long add(FeedbackRequest feedback) {
        if (feedback.correctedAnswer() == null || feedback.correctedAnswer().isBlank()) {
            return count();
        }
        try {
            Files.createDirectories(correctionsFile.getParent());
            String content = "Q: " + feedback.question() + "\nA: " + feedback.correctedAnswer();
            appendToLog(content);

            correctionStore.add(List.of(Document.builder()
                    .text(content)
                    .metadata("source", "correction:" + feedback.question())
                    .build()));
            correctionStore.save(storeFile.toFile());
            logger.info("Stored correction for question: {}", feedback.question());
            return count();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist correction", e);
        }
    }

    public SimpleVectorStore store() {
        return correctionStore;
    }

    public long count() {
        if (!Files.isRegularFile(correctionsFile)) {
            return 0;
        }
        try {
            return Files.readAllLines(correctionsFile).stream()
                    .filter(line -> line.startsWith("Q: "))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    private void appendToLog(String content) throws IOException {
        if (Files.exists(correctionsFile)) {
            Files.writeString(correctionsFile, content + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.APPEND);
        } else {
            Files.writeString(correctionsFile, content + System.lineSeparator());
        }
    }

    private void load() {
        if (Files.isRegularFile(storeFile)) {
            correctionStore.load(storeFile.toFile());
        }
    }
}
