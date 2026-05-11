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

@Service("deepseek")
public class DeepSeekService implements LLMService {

    @Value("${llm.providers.deepseek.api-key}")
    private String apiKey;

    @Value("${llm.providers.deepseek.base-url:https://api.deepseek.com}")
    private String deepseekBaseUrl;

    @Value("${llm.providers.deepseek.model:deepseek-chat}")
    private String model;

    @Value("${llm.providers.deepseek.temperature:0.1}")
    private double temperature;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String call(String prompt) throws Exception {
        return callDeepSeek(prompt);
    }

    public String callDeepSeek(String prompt) throws Exception {
        String systemPrompt = "You are an expert Spring Boot developer. Provide clean, well-documented code.";

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        payload.put("temperature", temperature);

        ArrayNode messages = payload.putArray("messages");

        ObjectNode systemMessage = messages.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", systemPrompt);

        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(deepseekBaseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        JsonNode responseNode = objectMapper.readTree(resp.body());

        // Log the full response for debugging
        if (responseNode.has("error")) {
            String errorMessage = responseNode.get("error").has("message") ?
                    responseNode.get("error").get("message").asText() : "Unknown error";
            throw new RuntimeException("DeepSeek API error: " + errorMessage);
        }

        if (!responseNode.has("choices") || responseNode.get("choices").isEmpty()) {
            throw new RuntimeException("Unexpected DeepSeek API response: " + resp.body());
        }

        return responseNode.get("choices").get(0)
                .get("message")
                .get("content")
                .asText();
    }
}