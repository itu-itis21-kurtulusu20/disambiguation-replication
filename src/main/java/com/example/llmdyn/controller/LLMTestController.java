package com.example.llmdyn.controller;

import com.example.llmdyn.llm.LLMServiceFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/llm")
public class LLMTestController {

    @Autowired
    private LLMServiceFactory llmServiceFactory;

    @PostMapping("/test")
    public ResponseEntity<String> testLLM(@RequestBody String prompt) {
        try {
            String response = llmServiceFactory.getService().call(prompt);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body("Error calling LLM: " + e.getMessage());
        }
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("LLM Service is running");
    }
}
