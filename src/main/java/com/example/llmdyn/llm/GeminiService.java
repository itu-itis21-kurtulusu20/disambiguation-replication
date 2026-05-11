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

@Service("gemini")
public class GeminiService implements LLMService {

    @Value("${llm.providers.gemini.api-key}")
    private String apiKey;

    @Value("${llm.providers.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}")
    private String baseUrl;

    @Value("${llm.providers.gemini.model:gemini-pro}")
    private String model;

    @Value("${llm.providers.gemini.temperature:0.1}")
    private double temperature;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String call(String prompt) throws Exception {
        return callGemini(prompt);
    }

    public String callGemini(String prompt) throws Exception {
        String systemPrompt = "You are an expert Spring Boot developer. Provide clean, well-documented code.";

        ObjectNode payload = objectMapper.createObjectNode();

        ArrayNode contents = payload.putArray("contents");
        ObjectNode content = contents.addObject();

        ArrayNode parts = content.putArray("parts");
        ObjectNode systemPart = parts.addObject();
        systemPart.put("text", systemPrompt);

        ObjectNode userPart = parts.addObject();
        userPart.put("text", prompt);

        ObjectNode generationConfig = payload.putObject("generationConfig");
        generationConfig.put("temperature", temperature);

        String url = String.format("%s/models/%s:generateContent?key=%s", baseUrl, model, apiKey);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            throw new RuntimeException("Gemini API error (status " + resp.statusCode() + "): " + resp.body());
        }

        return extractTextFromResponse(resp.body());
    }

    private String extractTextFromResponse(String responseBody) throws Exception {
        JsonNode response = objectMapper.readTree(responseBody);

        // Check for API errors
        if (response.has("error")) {
            String errorMessage = response.get("error").get("message").asText();
            throw new RuntimeException("Gemini API error: " + errorMessage);
        }

        // Navigate through the response structure with null checks
        JsonNode candidates = response.get("candidates");
        if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
            throw new RuntimeException("Invalid Gemini response - missing candidates. Response: " + responseBody);
        }

        JsonNode firstCandidate = candidates.get(0);
        if (firstCandidate == null) {
            throw new RuntimeException("Invalid Gemini response - null first candidate");
        }

        JsonNode content = firstCandidate.get("content");
        if (content == null) {
            throw new RuntimeException("Invalid Gemini response - missing content");
        }

        JsonNode parts = content.get("parts");
        if (parts == null || !parts.isArray() || parts.isEmpty()) {
            throw new RuntimeException("Invalid Gemini response - missing parts");
        }

        JsonNode firstPart = parts.get(0);
        if (firstPart == null) {
            throw new RuntimeException("Invalid Gemini response - null first part");
        }

        JsonNode text = firstPart.get("text");
        if (text == null) {
            throw new RuntimeException("Invalid Gemini response - missing text");
        }

        return text.asText();
    }
}