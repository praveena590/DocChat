package com.example.rag_app.controller;

import com.example.rag_app.service.OpenAiRagService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/docchat")
@CrossOrigin(origins = "*")
public class DocChatController {

    private final OpenAiRagService ragService;

    public DocChatController(OpenAiRagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadDocument(@RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(ragService.ingestFile(file));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/documents")
    public ResponseEntity<?> getDocuments() {
        return ResponseEntity.ok(ragService.listDocuments());
    }

    @PostMapping("/ask")
    public ResponseEntity<?> askQuestion(@RequestBody Map<String, String> payload) {
        String question = payload.get("question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Question is required"));
        }
        return ResponseEntity.ok(ragService.askQuestion(question));
    }

    @PostMapping("/api-key")
    public ResponseEntity<?> updateApiKey(@RequestBody Map<String, String> payload) {
        String newKey = payload.get("apiKey");
        if (newKey == null || newKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "apiKey cannot be empty"));
        }
        ragService.updateApiKey(newKey);
        return ResponseEntity.ok(Map.of("message", "OpenAI API key updated successfully"));
    }
}
