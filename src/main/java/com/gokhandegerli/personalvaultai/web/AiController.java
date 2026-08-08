package com.gokhandegerli.personalvaultai.web;

import com.gokhandegerli.personalvaultai.dto.ChatRequest;
import com.gokhandegerli.personalvaultai.dto.ChatResponse;
import com.gokhandegerli.personalvaultai.dto.ConversationDetail;
import com.gokhandegerli.personalvaultai.dto.ConversationSummary;
import com.gokhandegerli.personalvaultai.dto.FeedbackRequest;
import com.gokhandegerli.personalvaultai.dto.FeedbackResponse;
import com.gokhandegerli.personalvaultai.dto.IngestResponse;
import com.gokhandegerli.personalvaultai.service.ChatService;
import com.gokhandegerli.personalvaultai.service.CorrectionService;
import com.gokhandegerli.personalvaultai.service.IngestionService;
import com.gokhandegerli.personalvaultai.service.JsonChatMemory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AiController {

    private final IngestionService ingestionService;
    private final ChatService chatService;
    private final CorrectionService correctionService;
    private final JsonChatMemory chatMemory;

    public AiController(IngestionService ingestionService, ChatService chatService,
                        CorrectionService correctionService, JsonChatMemory chatMemory) {
        this.ingestionService = ingestionService;
        this.chatService = chatService;
        this.correctionService = correctionService;
        this.chatMemory = chatMemory;
    }

    @PostMapping("/ingest")
    public IngestResponse ingest() {
        return ingestionService.ingest();
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        String message = request.message();
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(chatService.ask(message, request.sessionId()));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestBody ChatRequest request) {
        String message = request.message();
        if (message == null || message.isBlank()) {
            return Flux.error(new IllegalArgumentException("message must not be blank"));
        }
        return chatService.stream(message, request.sessionId());
    }

    @GetMapping("/stores")
    public Map<String, Object> stores() {
        return ingestionService.stats();
    }

    @PostMapping("/feedback")
    public ResponseEntity<FeedbackResponse> feedback(@RequestBody FeedbackRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        long stored = correctionService.add(request);
        return ResponseEntity.ok(new FeedbackResponse(stored));
    }

    @GetMapping("/conversations")
    public List<ConversationSummary> conversations() {
        return chatMemory.list();
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<ConversationDetail> conversation(@PathVariable String id) {
        return chatMemory.detail(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Void> deleteConversation(@PathVariable String id) {
        return chatMemory.delete(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
