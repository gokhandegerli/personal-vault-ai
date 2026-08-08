package com.gokhandegerli.personalvaultai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gokhandegerli.personalvaultai.config.AppProperties;
import com.gokhandegerli.personalvaultai.dto.Conversation;
import com.gokhandegerli.personalvaultai.dto.ConversationDetail;
import com.gokhandegerli.personalvaultai.dto.ConversationSummary;
import com.gokhandegerli.personalvaultai.dto.Source;
import com.gokhandegerli.personalvaultai.dto.StoredMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class JsonChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(JsonChatMemory.class);

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int MAX_CONTEXT_MESSAGES = 20;
    private static final int MAX_TITLE_LENGTH = 60;
    private static final String RAG_CONTEXT_MARKER = "Context information is below";
    private static final String EXPANDED_QUERY_MARKER = "Previous conversation:";

    private final ObjectMapper objectMapper;
    private final Path dir;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public JsonChatMemory(AppProperties props) {
        this.objectMapper = new ObjectMapper();
        this.dir = Path.of(props.conversations().dir()).toAbsolutePath();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create conversations dir: " + dir, e);
        }
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (!isValidId(conversationId)) {
            log.warn("Ignoring invalid conversation id '{}'", conversationId);
            return;
        }
        synchronized (lock(conversationId)) {
            Conversation conversation = load(conversationId)
                    .orElseGet(() -> new Conversation(conversationId, Instant.now().toEpochMilli()));
            for (Message message : messages) {
                if (message != null && message.getText() != null) {
                    String text = stripRagContext(message.getText());
                    if (text != null && !text.isBlank()) {
                        conversation.getMessages().add(new StoredMessage(role(message), text, List.of()));
                    }
                }
            }
            updateTitle(conversation);
            conversation.setUpdatedAt(Instant.now().toEpochMilli());
            save(conversation);
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        if (!isValidId(conversationId)) {
            return List.of();
        }
        return load(conversationId)
                .map(Conversation::getMessages)
                .map(messages -> {
                    int from = Math.max(0, messages.size() - MAX_CONTEXT_MESSAGES);
                    return messages.subList(from, messages.size()).stream()
                            .map(JsonChatMemory::toSpringAiMessage)
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .toList();
                })
                .orElse(List.of());
    }

    @Override
    public void clear(String conversationId) {
        if (isValidId(conversationId)) {
            delete(conversationId);
        }
    }

    public void attachSources(String conversationId, List<Source> sources) {
        if (!isValidId(conversationId) || sources == null || sources.isEmpty()) {
            return;
        }
        synchronized (lock(conversationId)) {
            load(conversationId).ifPresent(conversation -> {
                List<StoredMessage> messages = conversation.getMessages();
                if (!messages.isEmpty() && "assistant".equals(messages.get(messages.size() - 1).role())) {
                    StoredMessage last = messages.get(messages.size() - 1);
                    messages.set(messages.size() - 1,
                            new StoredMessage(last.role(), last.content(), sources));
                    conversation.setUpdatedAt(Instant.now().toEpochMilli());
                    save(conversation);
                }
            });
        }
    }

    public List<ConversationSummary> list() {
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(this::load)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .map(c -> new ConversationSummary(
                            c.getId(),
                            c.getTitle(),
                            c.getMessages().size(),
                            c.getUpdatedAt()))
                    .sorted(Comparator.comparingLong(ConversationSummary::updatedAt).reversed())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list conversations in " + dir, e);
        }
    }

    public Optional<ConversationDetail> detail(String conversationId) {
        if (!isValidId(conversationId)) {
            return Optional.empty();
        }
        return load(conversationId)
                .map(c -> new ConversationDetail(
                        c.getId(),
                        c.getTitle(),
                        c.getCreatedAt(),
                        c.getUpdatedAt(),
                        List.copyOf(c.getMessages())));
    }

    public boolean delete(String conversationId) {
        if (!isValidId(conversationId)) {
            return false;
        }
        synchronized (lock(conversationId)) {
            locks.remove(conversationId);
            try {
                return Files.deleteIfExists(file(conversationId));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot delete conversation " + conversationId, e);
            }
        }
    }

    public static boolean isValidId(String id) {
        return id != null && VALID_ID.matcher(id).matches();
    }

    private Object lock(String conversationId) {
        return locks.computeIfAbsent(conversationId, k -> new Object());
    }

    private Optional<Conversation> load(String conversationId) {
        Path file = file(conversationId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), Conversation.class));
        } catch (IOException e) {
            log.error("Cannot read conversation '{}' from {}", conversationId, file, e);
            return Optional.empty();
        }
    }

    private Optional<Conversation> load(Path file) {
        try {
            Conversation conversation = objectMapper.readValue(file.toFile(), Conversation.class);
            return Optional.ofNullable(conversation);
        } catch (IOException e) {
            log.error("Cannot read conversation from {}", file, e);
            return Optional.empty();
        }
    }

    private void save(Conversation conversation) {
        Path target = file(conversation.getId());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            objectMapper.writeValue(tmp.toFile(), conversation);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write conversation " + conversation.getId(), e);
        }
    }

    private Path file(String conversationId) {
        return dir.resolve(conversationId + ".json");
    }

    private void updateTitle(Conversation conversation) {
        if (conversation.getTitle() != null && !conversation.getTitle().isBlank()) {
            return;
        }
        conversation.getMessages().stream()
                .filter(m -> "user".equals(m.role()))
                .map(m -> m.content().trim().replaceAll("\\s+", " "))
                .findFirst()
                .ifPresent(first -> {
                    String title = first.length() <= MAX_TITLE_LENGTH
                            ? first
                            : first.substring(0, MAX_TITLE_LENGTH) + "…";
                    conversation.setTitle(title);
                });
    }

    private static String role(Message message) {
        if (message instanceof UserMessage) {
            return "user";
        }
        if (message instanceof AssistantMessage) {
            return "assistant";
        }
        if (message instanceof SystemMessage) {
            return "system";
        }
        return "unknown";
    }

    private static String stripRagContext(String text) {
        String stripped = cutAt(text, RAG_CONTEXT_MARKER);
        if (stripped == null) {
            return null;
        }
        stripped = cutAt(stripped, EXPANDED_QUERY_MARKER);
        return stripped == null || stripped.isBlank() ? null : stripped.trim();
    }

    private static String cutAt(String text, String marker) {
        int idx = text.indexOf(marker);
        if (idx < 0) {
            return text;
        }
        String before = text.substring(0, idx).replaceAll("\n+\\s*$", "").trim();
        return before.isBlank() ? null : before;
    }

    private static Optional<Message> toSpringAiMessage(StoredMessage stored) {
        return switch (stored.role()) {
            case "user" -> Optional.of(new UserMessage(stored.content()));
            case "assistant" -> Optional.of(new AssistantMessage(stored.content()));
            case "system" -> Optional.of(new SystemMessage(stored.content()));
            default -> Optional.empty();
        };
    }
}
