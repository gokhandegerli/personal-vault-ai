package com.gokhandegerli.personalvaultai.web;

import com.gokhandegerli.personalvaultai.dto.ChatRequest;
import com.gokhandegerli.personalvaultai.dto.ChatResponse;
import com.gokhandegerli.personalvaultai.dto.IngestResponse;
import com.gokhandegerli.personalvaultai.service.ChatService;
import com.gokhandegerli.personalvaultai.service.IngestionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AiController {

    private final IngestionService ingestionService;
    private final ChatService chatService;

    public AiController(IngestionService ingestionService, ChatService chatService) {
        this.ingestionService = ingestionService;
        this.chatService = chatService;
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
        return ResponseEntity.ok(chatService.ask(message));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestBody ChatRequest request) {
        String message = request.message();
        if (message == null || message.isBlank()) {
            return Flux.error(new IllegalArgumentException("message must not be blank"));
        }
        return chatService.stream(message);
    }

    @GetMapping("/stores")
    public Map<String, Object> stores() {
        return ingestionService.stats();
    }
}
