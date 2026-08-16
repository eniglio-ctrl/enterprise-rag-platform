package com.eniglio.ragplatform.rag.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Small helpers shared between {@link RagQualityBenchmark} and {@link
 * ChunkingStrategyBenchmark} (Multi-LLM Phase 8) — extracted rather than duplicated
 * once a second benchmark class needed the exact same cosine-similarity/truncation
 * logic.
 */
final class BenchmarkSupport {

    private static final Path HISTORY_FILE = Path.of("src/test/resources/benchmark/history.csv");
    private static final String HISTORY_HEADER =
            "date,commit,questions,avgSimilarity,faithful,totalQuestions,avgContextRelevance,avgLatencyMs";

    private BenchmarkSupport() {
    }

    static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }

    /**
     * Multi-LLM Phase 16. {@code GIT_COMMIT} first (common CI env var, in case this
     * ever does run somewhere with that set), falling back to a real {@code git}
     * invocation for a local manual run — never throws: a benchmark run should never
     * fail just because the commit couldn't be resolved.
     */
    static String resolveGitCommitSha() {
        String fromEnv = System.getenv("GIT_COMMIT");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0 && !output.isEmpty() ? output : "unknown";
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "unknown";
        }
    }

    /**
     * Multi-LLM Phase 16. Appends one row to {@code src/test/resources/benchmark/
     * history.csv} — the real, git-tracked source file (not {@code
     * target/test-classes}), so a manual benchmark run leaves a comparable trace in
     * git history instead of only printing to stdout and vanishing. Writes the header
     * first if the file doesn't exist yet. Resolves relative to the working directory
     * Surefire already runs tests from (the module root, {@code rag-service/}) — the
     * same implicit assumption {@code ClassPathResource("benchmark/qa-pairs.json")}
     * already makes about where this module's test resources live.
     * <p>
     * {@code Locale.ROOT}, not the JVM default, in every {@code %f} — found for real
     * on the machine this was written on: its default locale renders a decimal point
     * as a comma, which silently corrupted the very first real row this ever wrote
     * (a comma is also this format's field separator). A machine-readable file like
     * this one must never depend on which locale happens to run the JVM.
     */
    static void appendHistoryRow(int questionCount, double avgSimilarity, int faithfulCount,
            double avgContextRelevance, double avgLatencyMs) {
        try {
            if (!Files.exists(HISTORY_FILE)) {
                Files.createDirectories(HISTORY_FILE.getParent());
                Files.writeString(HISTORY_FILE, HISTORY_HEADER + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE);
            }
            String row = String.format(Locale.ROOT, "%s,%s,%d,%.4f,%d,%d,%.4f,%.1f%n",
                    LocalDate.now(), resolveGitCommitSha(), questionCount, avgSimilarity, faithfulCount,
                    questionCount, avgContextRelevance, avgLatencyMs);
            Files.writeString(HISTORY_FILE, row, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Failed to append benchmark history row", e);
        }
    }
}
