package com.example.llmdyn.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service("claude")
public class ClaudeService implements LLMService {

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    @Value("${llm.providers.claude.api-key}")
    private String apiKey;

    @Value("${llm.providers.claude.base-url:https://api.anthropic.com/v1}")
    private String baseUrl;

    @Value("${llm.providers.claude.model:claude-sonnet-4-6}")
    private String model;

    @Value("${llm.providers.claude.temperature:0.1}")
    private double temperature;

    @Value("${llm.providers.claude.max-tokens:8096}")
    private int maxTokens;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String call(String prompt) throws Exception {
        return callClaude(prompt);
    }

    public String callClaude(String prompt) throws Exception {
        String systemPrompt = "You are an expert Spring Boot developer. Provide clean, well-documented code.";

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        payload.put("temperature", temperature);
        payload.put("max_tokens", maxTokens);
        payload.put("system", systemPrompt);

        ArrayNode messages = payload.putArray("messages");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            throw new RuntimeException("Claude API error (status " + resp.statusCode() + "): " + resp.body());
        }

        JsonNode responseNode = objectMapper.readTree(resp.body());

        if (responseNode.has("error")) {
            String errorMessage = responseNode.get("error").has("message")
                    ? responseNode.get("error").get("message").asText()
                    : "Unknown error";
            throw new RuntimeException("Claude API error: " + errorMessage);
        }

        // Response structure: { "content": [ { "type": "text", "text": "..." } ] }
        JsonNode content = responseNode.get("content");
        if (content == null || !content.isArray() || content.isEmpty()) {
            throw new RuntimeException("Unexpected Claude API response — missing content array: " + resp.body());
        }

        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) {
                return block.get("text").asText();
            }
        }

        throw new RuntimeException("Unexpected Claude API response — no text block found: " + resp.body());
    }
}