package com.gokhandegerli.personalvaultai.service;

import com.gokhandegerli.personalvaultai.config.AppProperties;
import com.gokhandegerli.personalvaultai.dto.IngestResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class IngestionService {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("md", "markdown", "docx");

    private final VectorStore vectorStore;
    private final AppProperties props;
    private final TokenTextSplitter splitter;
    private final AtomicInteger filesRead = new AtomicInteger();
    private final AtomicInteger chunksWritten = new AtomicInteger();

    public IngestionService(VectorStore vectorStore, AppProperties props) {
        this.vectorStore = vectorStore;
        this.props = props;
        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(props.rag().chunkSize())
                .withMinChunkSizeChars(200)
                .build();
    }

    public IngestResponse ingest() {
        Path root = resolveRoot();
        List<Path> files = collectFiles(root);
        List<Document> documents = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int processed = 0;

        for (Path file : files) {
            int max = props.rag().maxFiles();
            if (max > 0 && processed >= max) {
                break;
            }
            try {
                documents.addAll(readFile(file, root));
                processed++;
            } catch (Exception e) {
                skipped.add(rel(root, file) + ": " + e.getMessage());
            }
        }

        List<Document> chunks = splitter.apply(documents);
        vectorStore.write(chunks);
        persistIfSimpleStore();

        filesRead.set(processed);
        chunksWritten.set(chunks.size());
        return new IngestResponse(storeType(), processed, chunks.size(), skipped);
    }

    public Map<String, Object> stats() {
        return Map.of(
                "storeType", storeType(),
                "filesRead", filesRead.get(),
                "chunksWritten", chunksWritten.get());
    }

    private List<Path> collectFiles(Path root) {
        List<String> excluded = props.rag().excludedDirs();
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> SUPPORTED_EXTENSIONS.contains(extension(p)))
                    .filter(p -> excluded.stream().noneMatch(ex -> rel(root, p).startsWith(ex + "/") || rel(root, p).equals(ex)))
                    .sorted(Comparator.comparing(p -> rel(root, p)))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to walk vault at " + root, e);
        }
    }

    private List<Document> readFile(Path file, Path root) {
        String source = rel(root, file);
        var resource = new FileSystemResource(file);
        DocumentReader reader;
        if ("docx".equals(extension(file))) {
            reader = new TikaDocumentReader(resource);
        } else {
            reader = new MarkdownDocumentReader(resource,
                    MarkdownDocumentReaderConfig.builder()
                            .withAdditionalMetadata("source", source)
                            .build());
        }
        return reader.read();
    }

    private void persistIfSimpleStore() {
        if (vectorStore instanceof SimpleVectorStore simple) {
            Path file = props.vectorStore().simple().file();
            try {
                Files.createDirectories(file.getParent());
                simple.save(file.toFile());
            } catch (IOException e) {
                throw new IllegalStateException("Failed to persist SimpleVectorStore to " + file, e);
            }
        }
    }

    private Path resolveRoot() {
        Path path = props.rag().rootPath();
        if (!path.isAbsolute()) {
            path = Path.of("").toAbsolutePath().normalize().resolve(path);
        }
        return path.normalize();
    }

    private String storeType() {
        return vectorStore.getClass().getSimpleName();
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static String rel(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
