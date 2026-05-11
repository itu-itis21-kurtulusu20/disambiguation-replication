package com.example.llmdyn.controller;

import com.example.llmdyn.model.PromptProfile;
import com.example.llmdyn.service.TestRunner;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/test-runner")
public class TestRunnerController {

    private final TestRunner testRunner;

    public TestRunnerController(TestRunner testRunner) {
        this.testRunner = testRunner;
    }

    @GetMapping("/run-all")
    public ResponseEntity<?> runAllTests(
            @RequestParam(name = "promptProfile", required = false) String promptProfileRaw,
            @RequestParam(name = "includeProjectContext", required = false) Boolean includeProjectContext,
            @RequestParam(name = "group", required = false) String group,
            @RequestParam(name = "includeJpaGuidelines", required = false) Boolean includeJpaGuidelines
    ) {
        try {
            PromptProfile promptProfile = PromptProfile.from(promptProfileRaw);
            boolean effectiveIncludeProjectContext = includeProjectContext == null || includeProjectContext;
            return ResponseEntity.ok(
                    testRunner.runAllTests(promptProfile, effectiveIncludeProjectContext, group, includeJpaGuidelines)
            );
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }
}
