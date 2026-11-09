package com.example.rag_app.controller;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.rag_app.service.OpenAiRagService;
import com.example.rag_app.service.OpenAiRagService.DocumentRecord;

@RestController
@RequestMapping("/api/docchat")
public class DocChatController {

    private final OpenAiRagService ragService;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public DocChatController(OpenAiRagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/upload")
    public ResponseEntity<DocumentRecord> upload(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ragService.ingestFile(file));
    }

    @GetMapping("/documents")
    public List<DocumentRecord> documents() {
        return ragService.listDocuments();
    }

    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!ragService.deleteDocument(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found.");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody Map<String, String> payload) {
        return ragService.askQuestion(validQuestion(payload), payload.get("documentId"));
    }

    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestBody Map<String, String> payload) {
        String question = validQuestion(payload);
        String documentId = payload.get("documentId");
        SseEmitter emitter = new SseEmitter(180_000L);
        executor.submit(() -> ragService.streamAnswer(question, documentId, emitter));
        return emitter;
    }

    private String validQuestion(Map<String, String> payload) {
        String question = payload == null ? null : payload.get("question");
        if (question == null || question.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please enter a question.");
        }
        if (question.length() > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Question is too long (max 1000 characters).");
        }
        return question.trim();
    }
}