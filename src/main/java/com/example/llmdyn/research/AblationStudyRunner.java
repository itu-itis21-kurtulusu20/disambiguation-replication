package com.example.llmdyn.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs repeated ablation conditions against /api/test-runner/run-all and writes
 * per-run and summary CSVs for thesis reporting.
 */
public class AblationStudyRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    record Condition(
            String name,
            String baseUrl,
            String promptProfile,
            boolean includeProjectContext,
            Boolean includeJpaGuidelines
    ) {}

    record RunRow(
            String condition,
            int runIndex,
            String status,
            String error,
            boolean http5xx,
            int totalTests,
            int totalPassed,
            int totalFailed,
            double scenarioPassRate,
            int firstFailureDenominator,
            int cmpFirstFailureCount,
            double cmpFirstFailureRate,
            double elapsedSec
    ) {}

    record SpreadStats(double mean, double stddev, double min, double max) {}

    public static void main(String[] args) throws Exception {
        Map<String, String> cli = parseArgs(args);

        String baseUrl = cli.getOrDefault("baseUrl", "http://localhost:8080");
        String baseUrlA = cli.getOrDefault("baseUrlA", baseUrl);
        String baseUrlB = cli.getOrDefault("baseUrlB", baseUrlA);
        String group = cli.getOrDefault("group", "db-crud-jpa");
        int repeats = Integer.parseInt(cli.getOrDefault("repeats", "30"));
        Path outRoot = Path.of(cli.getOrDefault("outDir", "research-results"));
        double sleepSec = Double.parseDouble(cli.getOrDefault("sleepSec", "0.5"));

        String promptProfile = cli.getOrDefault("promptProfile", "ENHANCED");
        boolean includeProjectContext = Boolean.parseBoolean(cli.getOrDefault("includeProjectContext", "true"));
        String mode = cli.getOrDefault("mode", "explicit");
        boolean explicitMode = "explicit".equalsIgnoreCase(mode);

        boolean jpaA = Boolean.parseBoolean(cli.getOrDefault("jpaA", "true"));
        boolean jpaB = Boolean.parseBoolean(cli.getOrDefault("jpaB", "false"));

        List<Condition> conditions = List.of(
                new Condition(
                        "A",
                        baseUrlA,
                        promptProfile,
                        includeProjectContext,
                        explicitMode ? jpaA : null
                ),
                new Condition(
                        "B",
                        baseUrlB,
                        promptProfile,
                        includeProjectContext,
                        explicitMode ? jpaB : null
                )
        );

        Path runDir = outRoot.resolve("ablation-" + group + "-" + LocalDateTime.now().format(TS));
        Path rawDir = runDir.resolve("raw");
        Files.createDirectories(rawDir);

        HttpClient client = HttpClient.newHttpClient();
        List<RunRow> allRows = new ArrayList<>();

        printStartupArgs(args, mode, group, repeats, sleepSec, outRoot, runDir, conditions);

        for (int i = 1; i <= repeats; i++) {
            System.out.println("\n[ROUND] " + i + "/" + repeats + " (interleaved A/B)");
            for (Condition condition : conditions) {
                System.out.println("[CONDITION] " + condition.name()
                        + " | baseUrl=" + condition.baseUrl()
                        + " | promptProfile=" + condition.promptProfile()
                        + " | includeProjectContext=" + condition.includeProjectContext()
                        + " | includeJpaGuidelines="
                        + (condition.includeJpaGuidelines() == null ? "DEFAULT(null omitted)" : condition.includeJpaGuidelines()));
                long start = System.nanoTime();
                RunRow row;
                String url = buildUrl(group, condition);
                System.out.println("[RUN-START] condition=" + condition.name() + " run=" + i + "/" + repeats + " -> " + url);
                try {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

                    if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                        throw new IOException("HTTP " + resp.statusCode() + ": " + resp.body());
                    }

                    JsonNode json = MAPPER.readTree(resp.body());

                    Path rawFile = rawDir.resolve(condition.name + "-run" + String.format("%03d", i) + ".json");
                    Files.writeString(rawFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(json));

                    int totalTests = json.path("totalTests").asInt(0);
                    int totalPassed = json.path("totalPassed").asInt(0);
                    int totalFailed = json.path("totalFailed").asInt(0);
                    int ffDen = json.path("firstFailureDenominator").asInt(0);

                    int cmpCount = 0;
                    for (JsonNode n : json.path("firstFailureErrorCodeDistribution")) {
                        String code = n.path("errorCode").asText("");
                        int freq = n.path("frequency").asInt(0);
                        if (code.startsWith("CMP_")) {
                            cmpCount += freq;
                        }
                    }

                    double passRate = totalTests == 0 ? 0.0 : ((double) totalPassed / totalTests);
                    double cmpRate = ffDen == 0 ? 0.0 : ((double) cmpCount / ffDen);
                    double elapsedSecRun = (System.nanoTime() - start) / 1_000_000_000.0;

                    row = new RunRow(
                            condition.name,
                            i,
                            "OK",
                            "",
                            false,
                            totalTests,
                            totalPassed,
                            totalFailed,
                            passRate,
                            ffDen,
                            cmpCount,
                            cmpRate,
                            elapsedSecRun
                    );
                } catch (Exception ex) {
                    double elapsedSecRun = (System.nanoTime() - start) / 1_000_000_000.0;
                    row = new RunRow(
                            condition.name,
                            i,
                            "ERROR",
                            sanitize(ex.getMessage()),
                            isHttp5xx(ex),
                            0,
                            0,
                            0,
                            0.0,
                            0,
                            0,
                            0.0,
                            elapsedSecRun
                    );
                }

                allRows.add(row);
                printRunResult(row);
                Thread.sleep((long) (sleepSec * 1000));
            }
        }

        writeRunsCsv(runDir.resolve("runs.csv"), allRows);
        writeSummaryCsv(runDir.resolve("summary.csv"), allRows, conditions);
        printConsoleComparison(conditions, allRows);

        failFastIfSystemicServerFailure(runDir, allRows);

        System.out.println("[DONE] Results in: " + runDir.toAbsolutePath());
    }

    private static String buildUrl(String group, Condition c) {
        StringBuilder query = new StringBuilder();
        query.append("promptProfile=").append(enc(c.promptProfile()));
        query.append("&includeProjectContext=").append(enc(Boolean.toString(c.includeProjectContext())));
        query.append("&group=").append(enc(group));
        if (c.includeJpaGuidelines() != null) {
            query.append("&includeJpaGuidelines=").append(enc(Boolean.toString(c.includeJpaGuidelines())));
        }
        return c.baseUrl().replaceAll("/+$", "") + "/api/test-runner/run-all?" + query;
    }

    private static void writeRunsCsv(Path file, List<RunRow> rows) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            w.write("condition,runIndex,status,error,http5xx,totalTests,totalPassed,totalFailed,scenarioPassRate,firstFailureDenominator,cmpFirstFailureCount,cmpFirstFailureRate,elapsedSec");
            w.newLine();

            for (RunRow r : rows) {
                w.write(String.join(",",
                        csv(r.condition()),
                        Integer.toString(r.runIndex()),
                        csv(r.status()),
                        csv(r.error()),
                        Boolean.toString(r.http5xx()),
                        Integer.toString(r.totalTests()),
                        Integer.toString(r.totalPassed()),
                        Integer.toString(r.totalFailed()),
                        fmt(r.scenarioPassRate()),
                        Integer.toString(r.firstFailureDenominator()),
                        Integer.toString(r.cmpFirstFailureCount()),
                        fmt(r.cmpFirstFailureRate()),
                        fmt(r.elapsedSec())
                ));
                w.newLine();
            }
        }
    }

    private static void writeSummaryCsv(Path file, List<RunRow> rows, List<Condition> conditions) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            w.write("condition,okRuns,sumTotalTests,sumTotalPassed,sumTotalFailed,scenarioPassRate,scenarioPassRateCiLow,scenarioPassRateCiHigh,allPassRuns,allPassRunRate,allPassRunRateCiLow,allPassRunRateCiHigh,cmpFirstFailureRate,runPassRateMean,runPassRateStdDev,runPassRateMin,runPassRateMax");
            w.newLine();

            for (Condition c : conditions) {
                List<RunRow> ok = rows.stream()
                        .filter(r -> r.condition().equals(c.name()) && r.status().equals("OK"))
                        .sorted(Comparator.comparingInt(RunRow::runIndex))
                        .toList();

                int okRuns = ok.size();
                int sumTests = ok.stream().mapToInt(RunRow::totalTests).sum();
                int sumPassed = ok.stream().mapToInt(RunRow::totalPassed).sum();
                int sumFailed = ok.stream().mapToInt(RunRow::totalFailed).sum();

                double scenarioRate = sumTests == 0 ? 0.0 : ((double) sumPassed / sumTests);
                double[] scenarioCi = wilson(sumPassed, sumTests);

                int allPassRuns = (int) ok.stream().filter(r -> r.totalFailed() == 0).count();
                double allPassRate = okRuns == 0 ? 0.0 : ((double) allPassRuns / okRuns);
                double[] allPassCi = wilson(allPassRuns, okRuns);

                int ffDenSum = ok.stream().mapToInt(RunRow::firstFailureDenominator).sum();
                int cmpSum = ok.stream().mapToInt(RunRow::cmpFirstFailureCount).sum();
                double cmpRate = ffDenSum == 0 ? 0.0 : ((double) cmpSum / ffDenSum);
                SpreadStats spread = calculateSpread(ok);

                w.write(String.join(",",
                        csv(c.name()),
                        Integer.toString(okRuns),
                        Integer.toString(sumTests),
                        Integer.toString(sumPassed),
                        Integer.toString(sumFailed),
                        fmt(scenarioRate),
                        fmt(scenarioCi[0]),
                        fmt(scenarioCi[1]),
                        Integer.toString(allPassRuns),
                        fmt(allPassRate),
                        fmt(allPassCi[0]),
                        fmt(allPassCi[1]),
                        fmt(cmpRate),
                        fmt(spread.mean()),
                        fmt(spread.stddev()),
                        fmt(spread.min()),
                        fmt(spread.max())
                ));
                w.newLine();
            }
        }
    }

    private static SpreadStats calculateSpread(List<RunRow> okRows) {
        if (okRows == null || okRows.isEmpty()) {
            return new SpreadStats(0.0, 0.0, 0.0, 0.0);
        }

        int n = okRows.size();
        double sum = 0.0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;

        for (RunRow row : okRows) {
            double value = row.scenarioPassRate();
            sum += value;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }

        double mean = sum / n;
        if (n < 2) {
            return new SpreadStats(mean, 0.0, min, max);
        }

        double varianceSum = 0.0;
        for (RunRow row : okRows) {
            double diff = row.scenarioPassRate() - mean;
            varianceSum += diff * diff;
        }
        double stddev = Math.sqrt(varianceSum / (n - 1));
        return new SpreadStats(mean, stddev, min, max);
    }

    private static double[] wilson(int success, int total) {
        if (total <= 0) {
            return new double[]{0.0, 0.0};
        }
        double z = 1.96;
        double p = (double) success / total;
        double denom = 1.0 + (z * z) / total;
        double center = (p + (z * z) / (2.0 * total)) / denom;
        double margin = (z / denom) * Math.sqrt((p * (1.0 - p) / total) + ((z * z) / (4.0 * total * total)));
        double low = Math.max(0.0, center - margin);
        double high = Math.min(1.0, center + margin);
        return new double[]{low, high};
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].startsWith("--")) {
                out.put(args[i].substring(2), args[i + 1]);
                i++;
            }
        }
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String csv(String s) {
        String v = s == null ? "" : s;
        v = v.replace("\"", "\"\"");
        return "\"" + v + "\"";
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    private static String sanitize(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\n', ' ').replace('\r', ' ');
    }

    private static boolean isHttp5xx(Exception ex) {
        String message = ex == null ? "" : sanitize(ex.getMessage());
        return message.startsWith("HTTP 5");
    }

    private static void printStartupArgs(String[] rawArgs,
                                         String mode,
                                         String group,
                                         int repeats,
                                         double sleepSec,
                                         Path outRoot,
                                         Path runDir,
                                         List<Condition> conditions) {
        System.out.println("==================================================");
        System.out.println("[INFO] AblationStudyRunner starting");
        System.out.println("[ARGS-RAW] " + String.join(" ", rawArgs));
        System.out.println("[ARGS-PARSED] mode=" + mode
                + ", group=" + group
                + ", repeats=" + repeats
                + ", sleepSec=" + sleepSec
                + ", outDir=" + outRoot.toAbsolutePath());
        for (Condition c : conditions) {
            System.out.println("[PLAN] " + c.name()
                    + " => baseUrl=" + c.baseUrl()
                    + ", promptProfile=" + c.promptProfile()
                    + ", includeProjectContext=" + c.includeProjectContext()
                    + ", includeJpaGuidelines="
                    + (c.includeJpaGuidelines() == null ? "DEFAULT(null omitted)" : c.includeJpaGuidelines()));
        }
        System.out.println("[INFO] Output: " + runDir.toAbsolutePath());
        System.out.println("==================================================");
    }

    private static void printRunResult(RunRow row) {
        if ("OK".equals(row.status())) {
            System.out.println("[RUN-OK] condition=" + row.condition()
                    + " run=" + row.runIndex()
                    + " tests=" + row.totalTests()
                    + " passed=" + row.totalPassed()
                    + " failed=" + row.totalFailed()
                    + " passRate=" + fmt(row.scenarioPassRate())
                    + " cmpRate=" + fmt(row.cmpFirstFailureRate())
                    + " elapsedSec=" + fmt(row.elapsedSec()));
            return;
        }

        System.out.println("[RUN-ERROR] condition=" + row.condition()
                + " run=" + row.runIndex()
                + " http5xx=" + row.http5xx()
                + " elapsedSec=" + fmt(row.elapsedSec())
                + " error=" + row.error());
    }

    private static void printConsoleComparison(List<Condition> conditions, List<RunRow> rows) {
        System.out.println("\n==================== CONSOLE SUMMARY ====================");
        for (Condition c : conditions) {
            List<RunRow> ok = rows.stream()
                    .filter(r -> r.condition().equals(c.name()) && "OK".equals(r.status()))
                    .toList();

            int okRuns = ok.size();
            int sumTests = ok.stream().mapToInt(RunRow::totalTests).sum();
            int sumPassed = ok.stream().mapToInt(RunRow::totalPassed).sum();
            int sumFailed = ok.stream().mapToInt(RunRow::totalFailed).sum();
            int allPassRuns = (int) ok.stream().filter(r -> r.totalFailed() == 0).count();

            double passRate = sumTests == 0 ? 0.0 : ((double) sumPassed / sumTests);
            double[] passCi = wilson(sumPassed, sumTests);
            double allPassRate = okRuns == 0 ? 0.0 : ((double) allPassRuns / okRuns);
            double[] allPassCi = wilson(allPassRuns, okRuns);
            SpreadStats spread = calculateSpread(ok);

            System.out.println("[SUMMARY] " + c.name()
                    + " | okRuns=" + okRuns
                    + " | tests=" + sumTests
                    + " | passed=" + sumPassed
                    + " | failed=" + sumFailed
                    + " | passRate=" + fmt(passRate)
                    + " (CI95 " + fmt(passCi[0]) + "-" + fmt(passCi[1]) + ")"
                    + " | runMean=" + fmt(spread.mean())
                    + " | runStdDev=" + fmt(spread.stddev())
                    + " | runMin=" + fmt(spread.min())
                    + " | runMax=" + fmt(spread.max())
                    + " | allPassRunRate=" + fmt(allPassRate)
                    + " (CI95 " + fmt(allPassCi[0]) + "-" + fmt(allPassCi[1]) + ")");
        }

        if (conditions.size() == 2) {
            String a = conditions.get(0).name();
            String b = conditions.get(1).name();

            List<RunRow> okA = rows.stream().filter(r -> r.condition().equals(a) && "OK".equals(r.status())).toList();
            List<RunRow> okB = rows.stream().filter(r -> r.condition().equals(b) && "OK".equals(r.status())).toList();

            int testsA = okA.stream().mapToInt(RunRow::totalTests).sum();
            int passA = okA.stream().mapToInt(RunRow::totalPassed).sum();
            int testsB = okB.stream().mapToInt(RunRow::totalTests).sum();
            int passB = okB.stream().mapToInt(RunRow::totalPassed).sum();

            double rateA = testsA == 0 ? 0.0 : (double) passA / testsA;
            double rateB = testsB == 0 ? 0.0 : (double) passB / testsB;

            System.out.println("[DELTA] (B - A) scenarioPassRate=" + fmt(rateB - rateA)
                    + " (" + fmt((rateB - rateA) * 100.0) + " percentage points)");
        }
        System.out.println("=========================================================");
    }

    private static void failFastIfSystemicServerFailure(Path runDir, List<RunRow> rows) throws IOException {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        long okCount = rows.stream().filter(r -> "OK".equals(r.status())).count();
        long http5xxCount = rows.stream().filter(RunRow::http5xx).count();
        if (okCount > 0 || http5xxCount != rows.size()) {
            return;
        }

        String msg = "[FAIL-FAST] All runs failed due to HTTP 5xx from /api/test-runner/run-all. "
                + "This usually indicates backend runtime failure (e.g., closed EntityManagerFactory), "
                + "not a valid ablation outcome.";
        System.err.println(msg);

        Path diagnostics = runDir.resolve("diagnostics.txt");
        try (BufferedWriter w = Files.newBufferedWriter(diagnostics)) {
            w.write(msg);
            w.newLine();
            w.write("sampleError=" + rows.get(0).error());
            w.newLine();
            w.write("action=Check Spring logs and fix backend error before rerunning ablation.");
            w.newLine();
        }

        throw new IllegalStateException(msg + " Details written to: " + diagnostics.toAbsolutePath());
    }
}

