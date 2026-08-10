package com.gokhandegerli.personalvaultai.service;

import com.gokhandegerli.personalvaultai.config.AppProperties;
import com.gokhandegerli.personalvaultai.dto.IngestResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class IngestionService {

    private static final Logger logger = LoggerFactory.getLogger(IngestionService.class);

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("md", "markdown", "docx");
    private static final long INGEST_ADVISORY_LOCK_KEY = 725_001L;

    private final VectorStore vectorStore;
    private final AppProperties props;
    private final ObjectProvider<DataSource> dataSource;
    private final TokenTextSplitter splitter;
    private final ReentrantLock ingestLock = new ReentrantLock();
    private final AtomicInteger filesRead = new AtomicInteger();
    private final AtomicInteger chunksWritten = new AtomicInteger();
    private final AtomicInteger totalChunks = new AtomicInteger();
    private final AtomicInteger totalFiles = new AtomicInteger();
    private final AtomicReference<String> state = new AtomicReference<>("idle");
    private final AtomicReference<String> currentFile = new AtomicReference<>("");

    public IngestionService(VectorStore vectorStore, AppProperties props, ObjectProvider<DataSource> dataSource) {
        this.vectorStore = vectorStore;
        this.props = props;
        this.dataSource = dataSource;
        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(props.rag().chunkSize())
                .withMinChunkSizeChars(200)
                .build();
    }

    public IngestResponse ingestAsync() {
        if (!ingestLock.tryLock()) {
            return alreadyRunning();
        }
        DbAdvisoryLock advisoryLock;
        try {
            advisoryLock = acquireAdvisoryLock();
        } catch (SQLException e) {
            ingestLock.unlock();
            throw new IllegalStateException("Failed to acquire ingest advisory lock", e);
        }
        if (advisoryLock == null) {
            ingestLock.unlock();
            return alreadyRunning();
        }

        state.set("running");
        filesRead.set(0);
        chunksWritten.set(0);
        totalChunks.set(0);
        currentFile.set("");
        Path root = resolveRoot();
        totalFiles.set(collectFiles(root).size());

        CompletableFuture.runAsync(() -> {
            try {
                runIngest(root);
            } finally {
                advisoryLock.close();
                ingestLock.unlock();
            }
        });
        return new IngestResponse(storeType(), 0, 0, List.of());
    }

    private IngestResponse alreadyRunning() {
        return new IngestResponse(storeType(), -1, -1, List.of("ingest zaten çalışıyor"));
    }

    private DbAdvisoryLock acquireAdvisoryLock() throws SQLException {
        DataSource ds = dataSource.getIfAvailable();
        if (ds == null) {
            return new DbAdvisoryLock(null);
        }
        Connection connection = ds.getConnection();
        try {
            try (PreparedStatement ps = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, INGEST_ADVISORY_LOCK_KEY);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (!rs.getBoolean(1)) {
                        connection.close();
                        return null;
                    }
                }
            }
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return new DbAdvisoryLock(connection);
    }

    private void runIngest(Path root) {
        try {
            List<Path> files = collectFiles(root);
            List<Document> documents = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            int processed = 0;

            state.set("reading");
            for (Path file : files) {
                int max = props.rag().maxFiles();
                if (max > 0 && processed >= max) {
                    break;
                }
                currentFile.set(rel(root, file));
                try {
                    documents.addAll(readFile(file, root));
                    processed++;
                    filesRead.set(processed);
                } catch (Exception e) {
                    skipped.add(rel(root, file) + ": " + e.getMessage());
                }
            }

            state.set("embedding");
            currentFile.set("");
            List<Document> chunks = splitter.apply(documents);
            totalChunks.set(chunks.size());
            clearStore();

            int batchSize = 50;
            int total = chunks.size();
            for (int i = 0; i < total; i += batchSize) {
                List<Document> batch = chunks.subList(i, Math.min(i + batchSize, total));
                vectorStore.write(batch);
                chunksWritten.set(Math.min(i + batchSize, total));
                persistIfSimpleStore();
                logger.info("Embedded {}/{} chunks", chunksWritten.get(), total);
            }

            chunksWritten.set(total);
            filesRead.set(processed);
            logger.info("Ingest complete: {} files, {} chunks, {} skipped", processed, chunks.size(), skipped.size());
        } catch (Exception e) {
            logger.error("Ingest failed", e);
        } finally {
            state.set("idle");
            currentFile.set("");
        }
    }

    public IngestResponse ingest() {
        return ingestAsync();
    }

    public Map<String, Object> stats() {
        return Map.of(
                "storeType", storeType(),
                "state", state.get(),
                "filesRead", filesRead.get(),
                "totalFiles", totalFiles.get(),
                "chunksWritten", chunksWritten.get(),
                "totalChunks", totalChunks.get(),
                "currentFile", currentFile.get());
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
        if ("docx".equals(extension(file))) {
            return new TikaDocumentReader(new FileSystemResource(file)).read();
        }
        try {
            String text = Files.readString(file);
            if (text.isBlank()) {
                return List.of();
            }
            return List.of(Document.builder()
                    .text(text)
                    .metadata("source", source)
                    .build());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + source, e);
        }
    }

    private static final String CLEAR_SENTINEL = "__pva_clear_all__";

    private void clearStore() {
        try {
            vectorStore.delete(new FilterExpressionBuilder().ne("source", CLEAR_SENTINEL).build());
        } catch (Exception e) {
            logger.warn("Failed to clear existing store before ingest: {}", e.getMessage());
        }
    }

    private void persistIfSimpleStore() {
        if (vectorStore instanceof SimpleVectorStore simple) {
            Path file = Path.of(props.vectorStore().simple().file());
            try {
                Files.createDirectories(file.getParent());
                simple.save(file.toFile());
            } catch (IOException e) {
                throw new IllegalStateException("Failed to persist SimpleVectorStore to " + file, e);
            }
        }
    }

    private Path resolveRoot() {
        Path path = Path.of(props.rag().rootPath());
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

    private static final class DbAdvisoryLock implements AutoCloseable {

        private final Connection connection;

        DbAdvisoryLock(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void close() {
            if (connection == null) {
                return;
            }
            try {
                try (Statement st = connection.createStatement()) {
                    st.execute("SELECT pg_advisory_unlock(" + INGEST_ADVISORY_LOCK_KEY + ")");
                }
            } catch (SQLException e) {
                logger.warn("Failed to release ingest advisory lock: {}", e.getMessage());
            } finally {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // ignore
                }
            }
        }
    }
}
