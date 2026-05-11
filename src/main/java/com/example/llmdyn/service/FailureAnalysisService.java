package com.example.llmdyn.service;

import com.example.llmdyn.llm.LLMServiceFactory;
import com.example.llmdyn.model.FailureDetail;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.io.StringWriter;

@Service
public class FailureAnalysisService {

    @Value("${analysis.enabled:true}")
    private boolean enabled;

    @Value("${analysis.max-source-chars:4000}")
    private int maxSourceChars;

    @Value("${analysis.max-stack-chars:2500}")
    private int maxStackChars;

    private final LLMServiceFactory llmServiceFactory;

    public FailureAnalysisService(LLMServiceFactory llmServiceFactory) {
        this.llmServiceFactory = llmServiceFactory;
    }

    public String analyzeFailure(String stage,
                                 String prompt,
                                 String fullClassName,
                                 String generatedSource,
                                 String errorMessage,
                                 Throwable throwable,
                                 FailureDetail failureDetail) {
        String deterministicNote = buildDeterministicNote(errorMessage, throwable, failureDetail);
        if (!enabled) {
            return deterministicNote != null
                    ? deterministicNote
                    : "LLM failure analysis disabled by configuration.";
        }

        String analysisPrompt = buildAnalysisPrompt(
                stage,
                prompt,
                fullClassName,
                generatedSource,
                errorMessage,
                throwable,
                failureDetail
        );

        try {
            return llmServiceFactory.getService().call(analysisPrompt);
        } catch (Exception ex) {
            return deterministicNote != null
                    ? deterministicNote
                    : "LLM failure analysis unavailable: " + ex.getMessage();
        }
    }

    private String buildAnalysisPrompt(String stage,
                                       String prompt,
                                       String fullClassName,
                                       String generatedSource,
                                       String errorMessage,
                                       Throwable throwable,
                                       FailureDetail failureDetail) {
        String errorCode = failureDetail == null || failureDetail.getErrorCode() == null
                ? "UNCLASSIFIED"
                : failureDetail.getErrorCode().name();
        String category = failureDetail == null ? "unknown" : failureDetail.getCategory();

        return "You are analyzing a dynamic Java code generation failure. " +
                "Provide concise notes for a research log.\n\n" +
                "Return EXACTLY this format:\n" +
                "RootCause: <one sentence>\n" +
                "WhyItHappened: <2-3 bullet-like clauses in one line>\n" +
                "SuggestedFixes: <up to 3 short actionable fixes separated by '; '>\n" +
                "Confidence: <low|medium|high>\n\n" +
                "If evidence is insufficient, explicitly state 'insufficient evidence' and list what is missing; do not guess.\n\n" +
                "Context:\n" +
                "Stage: " + safe(stage) + "\n" +
                "ErrorCode: " + errorCode + "\n" +
                "Category: " + category + "\n" +
                "RequestedClass: " + safe(fullClassName) + "\n" +
                "Prompt: " + safe(prompt) + "\n" +
                "ErrorMessage: " + safe(errorMessage) + "\n" +
                "ExceptionStack:\n" + truncate(stackTraceOf(throwable), maxStackChars) + "\n\n" +
                "GeneratedSource:\n" + truncate(safe(generatedSource), maxSourceChars);
    }

    private String stackTraceOf(Throwable throwable) {
        if (throwable == null) {
            return "N/A";
        }
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "N/A";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "N/A" : value;
    }

    private String buildDeterministicNote(String errorMessage,
                                          Throwable throwable,
                                          FailureDetail failureDetail) {
        if (failureDetail == null || failureDetail.getErrorCode() == null) {
            return null;
        }

        String code = failureDetail.getErrorCode().name();
        String combinedEvidence = (safe(errorMessage) + " "
                + safe(failureDetail.getRootCauseMessage()) + " "
                + safe(failureDetail.getExceptionType()) + " "
                + safe(failureDetail.getRootCauseType())).toLowerCase();

        if ("DB_SQL_EXCEPTION".equals(code) || "DB_PERSISTENCE_EXCEPTION".equals(code)) {
            if (combinedEvidence.contains("entitymanagerfactory is closed")) {
                return "RootCause: EntityManagerFactory was closed before a database operation was attempted.\n"
                        + "WhyItHappened: dynamic cleanup/recreation closed the active EMF; subsequent persistence access reused a stale lifecycle path; failure surfaced in DB stage.\n"
                        + "SuggestedFixes: avoid stale EMF references in cleanup services; use JDBC-based cleanup for truncation; keep EMF recreation sequence isolated from concurrent request handling.\n"
                        + "Confidence: high";
            }

            if (combinedEvidence.contains("unable to rollback against jdbc connection")
                    || combinedEvidence.contains("transactionexception")
                    || combinedEvidence.contains("connection is closed")) {
                return "RootCause: JDBC connection was already closed when transaction rollback/execution was attempted.\n"
                        + "WhyItHappened: transaction lifecycle reached rollback with a closed pooled connection; earlier DB operation/cleanup likely invalidated active connection context; persistence layer propagated rollback failure.\n"
                        + "SuggestedFixes: ensure connection/transaction scope is not reused after cleanup; avoid EMF/connection invalidation during in-flight requests; add defensive checks and recreate transaction boundary before DB calls.\n"
                        + "Confidence: high";
            }

            if (throwable == null || "N/A".equals(safe(failureDetail.getRootCauseMessage()))) {
                return "RootCause: insufficient evidence to identify an exact SQL root cause (classified as DB_SQL_EXCEPTION).\n"
                        + "WhyItHappened: captured error lacks concrete root-cause message/stack details; classifier matched broad DB patterns; LLM cannot infer specifics reliably.\n"
                        + "SuggestedFixes: persist full throwable cause chain; include SQLState/vendor code in logs; enrich failure payload with root exception text before analysis.\n"
                        + "Confidence: medium";
            }
        }

        if ("EXEC_ASSERTION_MISMATCH".equals(code)) {
            String details = safe(errorMessage);
            return "RootCause: Generated behavior does not match expected test output for the invoked method.\n"
                    + "WhyItHappened: assertion comparison failed in dynamic execution; method output diverged from expected values; details=" + truncate(details, 400) + ".\n"
                    + "SuggestedFixes: inspect generated method logic for field mapping and update semantics; validate argument conversion and numeric/date handling; add targeted prompt constraints for the failing method contract.\n"
                    + "Confidence: medium";
        }

        if (combinedEvidence.contains("entitymanagerfactory is closed")) {
            return "RootCause: EntityManagerFactory lifecycle was closed before request processing completed.\n"
                    + "WhyItHappened: persistence infrastructure attempted to open an EntityManager after EMF closure; runtime lifecycle ordering caused stale access; failure propagated to the current stage.\n"
                    + "SuggestedFixes: ensure EMF recreation is serialized with request flow; disable OpenEntityManagerInView for experiment runs; add lifecycle guards before DB actions.\n"
                    + "Confidence: high";
        }

        return null;
    }
}
