package com.example.rag_app.service;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;

@Service
public class OpenAiRagService {

    static final String NOT_FOUND = "I don't know based on the provided document.";
    // NVIDIA embedding endpoints can reject large batches, so keep this small.
    private static final int EMBED_BATCH = 32;
    private static final int CHUNK_SIZE = 700;
    private static final int CHUNK_OVERLAP = 120;

    public record DocumentChunk(String chunkId, String docId, String fileName, String content, List<Double> embedding) {}
    public record DocumentRecord(String id, String fileName, long fileSize, int chunkCount, String uploadedAt, String status) {}
    public record CitationMatch(String chunkId, String fileName, String snippet, double score) {}
    public record LocalStoreData(List<DocumentChunk> chunks, List<DocumentRecord> documents) {}

    private record Match(DocumentChunk chunk, double score) {}

    private final RestClient restClient;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String baseUrl;
    private final String apiKey;
    private final String embeddingModel;
    private final String chatModel;
    private final double minScore;
    private final int topK;
    private final int maxChunks;

    private final Tika tika = new Tika();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final File localStoreFile = new File("vector-store.json");

    private final List<DocumentChunk> vectorStore = new CopyOnWriteArrayList<>();
    private final List<DocumentRecord> documentRegistry = new CopyOnWriteArrayList<>();

    public OpenAiRagService(
            @Value("${openai.api.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${openai.api.key}") String apiKey,
            @Value("${openai.embedding.model:text-embedding-3-small}") String embeddingModel,
            @Value("${openai.chat.model:gpt-4o-mini}") String chatModel,
            @Value("${rag.min-score:0.25}") double minScore,
            @Value("${rag.top-k:4}") int topK,
            @Value("${rag.max-chunks:300}") int maxChunks) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
        this.minScore = minScore;
        this.topK = topK;
        this.maxChunks = maxChunks;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    // ---------- persistence ----------

    @PostConstruct
    public void loadFromDisk() {
        if (localStoreFile.exists()) {
            try {
                LocalStoreData data = objectMapper.readValue(localStoreFile, new TypeReference<>() {});
                if (data.chunks() != null) vectorStore.addAll(data.chunks());
                if (data.documents() != null) documentRegistry.addAll(data.documents());
            } catch (Exception e) {
                System.err.println("Could not load vector-store.json: " + e.getMessage());
            }
        }
    }

    private synchronized void saveToDisk() {
        try {
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(localStoreFile, new LocalStoreData(vectorStore, documentRegistry));
        } catch (Exception e) {
            System.err.println("Could not save vector-store.json: " + e.getMessage());
        }
    }

    // ---------- documents ----------

    public DocumentRecord ingestFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please choose a file to upload.");
        }

        String text;
        try {
            text = tika.parseToString(file.getInputStream());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Could not read this file.");
        }

        List<String> chunks = splitIntoChunks(text, CHUNK_SIZE, CHUNK_OVERLAP);
        if (chunks.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No readable text found. Scanned PDFs are not supported.");
        }
        if (chunks.size() > maxChunks) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "This document is too long for the demo. Try a smaller file.");
        }

        // "passage" = document text being stored for later search
        List<List<Double>> vectors = fetchEmbeddings(chunks, "passage");

        String docId = UUID.randomUUID().toString();
        String fileName = Objects.requireNonNullElse(file.getOriginalFilename(), "Untitled");
        String prefix = docId.substring(0, 8);

        for (int i = 0; i < chunks.size(); i++) {
            vectorStore.add(new DocumentChunk(prefix + "-" + (i + 1), docId, fileName, chunks.get(i), vectors.get(i)));
        }

        DocumentRecord record = new DocumentRecord(docId, fileName, file.getSize(), chunks.size(),
                Instant.now().toString(), "READY");
        documentRegistry.add(record);
        saveToDisk();
        return record;
    }

    public List<DocumentRecord> listDocuments() {
        return documentRegistry;
    }

    public boolean deleteDocument(String id) {
        boolean removed = documentRegistry.removeIf(d -> d.id().equals(id));
        vectorStore.removeIf(c -> c.docId().equals(id));
        if (removed) saveToDisk();
        return removed;
    }

    // ---------- asking ----------

    public Map<String, Object> askQuestion(String question, String documentId) {
        List<Match> matches = retrieve(question, documentId);
        if (matches.isEmpty()) {
            return Map.of("question", question, "answer", NOT_FOUND, "citations", List.of());
        }
        String answer = fetchChatCompletion(buildContext(matches), question);
        return Map.of("question", question, "answer", answer, "citations", toCitations(matches));
    }

    public void streamAnswer(String question, String documentId, SseEmitter emitter) {
        try {
            List<Match> matches = retrieve(question, documentId);
            send(emitter, "sources", toCitations(matches));

            if (matches.isEmpty()) {
                send(emitter, "token", Map.of("text", NOT_FOUND));
            } else {
                streamChat(buildContext(matches), question, token -> {
                    try {
                        send(emitter, "token", Map.of("text", token));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
            send(emitter, "done", Map.of("ok", true));
            emitter.complete();
        } catch (Exception e) {
            e.printStackTrace();
            String message = e instanceof ResponseStatusException r && r.getReason() != null
                    ? r.getReason() : "Something went wrong while generating the answer.";
            try {
                send(emitter, "error", Map.of("error", message));
            } catch (IOException ignored) {
                // client disconnected
            }
            emitter.complete();
        }
    }

    private List<Match> retrieve(String question, String documentId) {
        if (documentId != null && documentRegistry.stream().noneMatch(d -> d.id().equals(documentId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found. Please upload it again.");
        }
        if (vectorStore.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload a document first.");
        }

        // "query" = the user's question being matched against stored passages
        List<Double> queryVector = fetchEmbeddings(List.of(question), "query").get(0);

        return vectorStore.stream()
                .filter(c -> documentId == null || c.docId().equals(documentId))
                // skip vectors stored by a different embedding model (different size)
                .filter(c -> c.embedding() != null && c.embedding().size() == queryVector.size())
                .map(c -> new Match(c, cosineSimilarity(queryVector, c.embedding())))
                .filter(m -> m.score() >= minScore)
                .sorted(Comparator.comparingDouble(Match::score).reversed())
                .limit(topK)
                .toList();
    }

    private String buildContext(List<Match> matches) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) {
            sb.append('[').append(i + 1).append("] ").append(matches.get(i).chunk().content()).append("\n\n");
        }
        return sb.toString();
    }

    private List<CitationMatch> toCitations(List<Match> matches) {
        return matches.stream().map(m -> {
            String content = m.chunk().content();
            String snippet = content.length() <= 160 ? content : content.substring(0, 160) + "...";
            return new CitationMatch(m.chunk().chunkId(), m.chunk().fileName(), snippet,
                    Math.round(m.score() * 1000.0) / 1000.0);
        }).toList();
    }

    // ---------- AI API calls (NVIDIA NIM / OpenAI-compatible) ----------

    // NVIDIA retrieval models need input_type/truncate; other providers (e.g. Ollama) don't.
    private Map<String, Object> embeddingBody(List<String> batch, String inputType) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("input", batch);
        body.put("model", embeddingModel);
        if (baseUrl.contains("nvidia.com")) {
            body.put("input_type", inputType);
            body.put("encoding_format", "float");
            body.put("truncate", "END");
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    private List<List<Double>> fetchEmbeddings(List<String> inputs, String inputType) {
        List<List<Double>> all = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i += EMBED_BATCH) {
            List<String> batch = inputs.subList(i, Math.min(i + EMBED_BATCH, inputs.size()));
            Map<String, Object> response = callApi(() -> restClient.post()
                    .uri("/embeddings")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(embeddingBody(batch, inputType))
                    .retrieve()
                    .body(Map.class));

            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
            data.stream()
                    .sorted(Comparator.comparingInt(d -> ((Number) d.get("index")).intValue()))
                    .forEach(d -> all.add(((List<Number>) d.get("embedding")).stream()
                            .map(Number::doubleValue).toList()));
        }
        return all;
    }

    @SuppressWarnings("unchecked")
    private String fetchChatCompletion(String context, String question) {
        Map<String, Object> response = callApi(() -> restClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .body(chatBody(context, question, false))
                .retrieve()
                .body(Map.class));

        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return (String) message.get("content");
    }

    private void streamChat(String context, String question, Consumer<String> onToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(chatBody(context, question, true))))
                    .build();

            HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                String body;
                try (Stream<String> errLines = response.body()) {
                    body = errLines.collect(Collectors.joining("\n"));
                }
                System.err.println("Chat API error " + response.statusCode() + ": " + body);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, apiMessage(response.statusCode()));
            }

            try (Stream<String> lines = response.body()) {
                var it = lines.iterator();
                while (it.hasNext()) {
                    String line = it.next().trim();
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if (payload.equals("[DONE]")) break;
                    JsonNode node = objectMapper.readTree(payload);
                    String token = node.path("choices").path(0).path("delta").path("content").asText("");
                    if (!token.isEmpty()) onToken.accept(token);
                }
            }
        } catch (IOException e) {
            System.err.println("Chat stream failed: " + e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not reach the AI API.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Request was interrupted.");
        }
    }

    private Map<String, Object> chatBody(String context, String question, boolean stream) {
        String system = """
                You are DocChat. Answer using ONLY the numbered context passages below.
                Cite the passages you used like [1] or [2].
                If the answer is not in the context, reply exactly: "%s"

                Context:
                %s""".formatted(NOT_FOUND, context);

        return Map.of(
                "model", chatModel,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", question)),
                "temperature", 0.2,
                "max_tokens", 800,
                "stream", stream);
    }

    private <T> T callApi(Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            // Print the provider's real error message so you can see what is wrong
            System.err.println("API error " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, apiMessage(e.getStatusCode().value()));
        } catch (RestClientException e) {
            System.err.println("API call failed: " + e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not reach the AI API.");
        }
    }

    private String apiMessage(int status) {
        return switch (status) {
            case 400 -> "The AI API rejected the request (status 400). Check the server console for details.";
            case 401 -> "The AI API rejected the API key.";
            case 404 -> "AI API returned 404. Check the model names and base URL in application.properties.";
            case 429 -> "AI API rate limit or quota exceeded.";
            default -> "AI API error (status " + status + ").";
        };
    }

    private void send(SseEmitter emitter, String name, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
    }

    // ---------- helpers ----------

    private List<String> splitIntoChunks(String text, int size, int overlap) {
        List<String> result = new ArrayList<>();
        String clean = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        int start = 0;
        while (start < clean.length()) {
            int end = Math.min(start + size, clean.length());
            if (end < clean.length()) {
                int space = clean.lastIndexOf(' ', end);
                if (space > start + size / 2) end = space;
            }
            String chunk = clean.substring(start, end).trim();
            if (!chunk.isEmpty()) result.add(chunk);
            if (end >= clean.length()) break;
            start = Math.max(end - overlap, start + 1);
        }
        return result;
    }

    private double cosineSimilarity(List<Double> a, List<Double> b) {
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB) + 1e-10);
    }
}