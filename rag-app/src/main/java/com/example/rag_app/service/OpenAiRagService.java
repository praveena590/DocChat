package com.example.rag_app.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class OpenAiRagService {

    private final RestClient restClient;
    private volatile String apiKey;
    private final String embeddingModel;
    private final String chatModel;
    private final Tika tika = new Tika();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final File localStoreFile = new File("vector-store.json");

    public record DocumentChunk(String chunkId, String docId, String fileName, String content, List<Double> embedding) {}
    public record DocumentRecord(String id, String fileName, long fileSize, int chunkCount, String uploadedAt, String status) {}
    public record CitationMatch(String chunkId, String fileName, String snippet, double score) {}
    public record LocalStoreData(List<DocumentChunk> chunks, List<DocumentRecord> documents) {}

    // In-Memory Storage (No Database Needed)
    private final List<DocumentChunk> vectorStore = new CopyOnWriteArrayList<>();
    private final List<DocumentRecord> documentRegistry = new CopyOnWriteArrayList<>();

    public OpenAiRagService(
            @Value("${openai.api.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${openai.api.key}") String apiKey,
            @Value("${openai.embedding.model:text-embedding-3-small}") String embeddingModel,
            @Value("${openai.chat.model:gpt-4o-mini}") String chatModel) {
        this.apiKey = apiKey;
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @PostConstruct
    public void loadFromDisk() {
        if (localStoreFile.exists()) {
            try {
                LocalStoreData data = objectMapper.readValue(localStoreFile, new TypeReference<>() {});
                if (data.chunks() != null) vectorStore.addAll(data.chunks());
                if (data.documents() != null) documentRegistry.addAll(data.documents());
            } catch (Exception e) {
                System.err.println("Could not load local vector-store.json: " + e.getMessage());
            }
        }
    }

    private void saveToDisk() {
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(localStoreFile, new LocalStoreData(vectorStore, documentRegistry));
        } catch (Exception e) {
            System.err.println("Could not save local vector-store.json: " + e.getMessage());
        }
    }

    public void updateApiKey(String newApiKey) {
        this.apiKey = newApiKey.trim();
    }

    public DocumentRecord ingestFile(MultipartFile file) throws Exception {
        String docId = UUID.randomUUID().toString();
        String fileName = Objects.requireNonNullElse(file.getOriginalFilename(), "Untitled.pdf");
        String text = tika.parseToString(file.getInputStream());
        List<String> chunks = splitIntoChunks(text, 700, 120);

        int idx = 1;
        for (String chunk : chunks) {
            List<Double> vector = fetchEmbedding(chunk);
            vectorStore.add(new DocumentChunk(
                    "chunk-" + idx++,
                    docId,
                    fileName,
                    chunk,
                    vector
            ));
        }

        DocumentRecord record = new DocumentRecord(
                docId,
                fileName,
                file.getSize(),
                chunks.size(),
                Instant.now().toString(),
                "INDEXED (OPENAI)"
        );
        documentRegistry.add(record);
        saveToDisk();
        return record;
    }

    public List<DocumentRecord> listDocuments() {
        return documentRegistry;
    }

    public Map<String, Object> askQuestion(String question) {
        List<Double> queryVector = fetchEmbedding(question);

        List<CitationMatch> topMatches = vectorStore.stream()
                .map(c -> new CitationMatch(
                        c.chunkId(),
                        c.fileName(),
                        c.content().substring(0, Math.min(160, c.content().length())) + "...",
                        Math.round(cosineSimilarity(queryVector, c.embedding()) * 1000.0) / 1000.0
                ))
                .sorted(Comparator.comparingDouble(CitationMatch::score).reversed())
                .limit(4)
                .toList();

        String context = String.join("\n\n---\n\n",
                topMatches.stream().map(m -> "[" + m.fileName() + " | " + m.chunkId() + "]: " + m.snippet()).toList());

        String answer = fetchChatCompletion(context, question);

        return Map.of(
                "question", question,
                "answer", answer,
                "citations", topMatches
        );
    }

    @SuppressWarnings("unchecked")
    private List<Double> fetchEmbedding(String input) {
        Map<String, Object> body = Map.of(
                "input", input,
                "model", embeddingModel
        );
        Map response = restClient.post()
                .uri("/embeddings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + this.apiKey)
                .body(body)
                .retrieve()
                .body(Map.class);
        
        List data = (List) response.get("data");
        Map firstItem = (Map) data.get(0);
        return (List<Double>) firstItem.get("embedding");
    }

    @SuppressWarnings("unchecked")
    private String fetchChatCompletion(String context, String question) {
        String systemPrompt = "You are DocChat Studio AI. Answer using ONLY the provided context and mention the source file names.\n\nContext:\n" + context;
        Map<String, Object> body = Map.of(
                "model", chatModel,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", question)
                ),
                "temperature", 0.2,
                "max_tokens", 800
        );
        Map response = restClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + this.apiKey)
                .body(body)
                .retrieve()
                .body(Map.class);
        
        List choices = (List) response.get("choices");
        Map firstChoice = (Map) choices.get(0);
        Map message = (Map) firstChoice.get("message");
        return (String) message.get("content");
    }	

    private List<String> splitIntoChunks(String text, int size, int overlap) {
        List<String> result = new ArrayList<>();
        String clean = text.replaceAll("\\s+", " ").trim();
        int start = 0;
        while (start < clean.length()) {
            int end = Math.min(start + size, clean.length());
            result.add(clean.substring(start, end));
            if (end == clean.length()) break;
            start += (size - overlap);
        }
        return result;
    }

    private double cosineSimilarity(List<Double> a, List<Double> b) {
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            normA += Math.pow(a.get(i), 2);
            normB += Math.pow(b.get(i), 2);
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB) + 1e-10);
    }
}