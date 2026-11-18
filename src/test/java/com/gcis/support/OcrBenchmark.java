package com.gcis.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Offline OCR accuracy benchmark against a harvested sample corpus.
 *
 * Corpus layout (see README - OCR tuning):
 *   <dir>/cap-NN.jpg            - captcha images
 *   <dir>/ground-truth.csv      - "file,answer" lines (answer from the test
 *                                  env's captchaForACT field)
 *
 * Usage:
 *   mvn test-compile exec:java \
 *       -Dexec.mainClass=com.gcis.support.OcrBenchmark \
 *       -Dexec.classpathScope=test -Dexec.args=<corpus-dir>
 *
 * Prints exact-match rate and per-character accuracy, both as-is and
 * upper-cased (the observed captcha charset is uppercase letters + digits),
 * plus a mismatch table for tuning.
 */
public final class OcrBenchmark {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: OcrBenchmark <corpus-dir>");
            System.exit(2);
        }
        Path dir = Paths.get(args[0]);
        Path csv = dir.resolve("ground-truth.csv");
        if (!Files.isRegularFile(csv)) {
            System.err.println("ground-truth.csv not found in " + dir);
            System.exit(2);
        }

        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(csv, StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("file,")) {
                continue;
            }
            String[] parts = line.split(",", 2);
            if (parts.length == 2 && Files.isRegularFile(dir.resolve(parts[0]))) {
                rows.add(parts);
            }
        }
        if (rows.isEmpty()) {
            System.err.println("no usable rows in " + csv);
            System.exit(2);
        }

        int exact = 0;
        int exactUpper = 0;
        int chars = 0;
        int charsUpper = 0;
        List<String> misses = new ArrayList<>();
        long startedAt = System.currentTimeMillis();

        for (String[] row : rows) {
            String file = row[0];
            String truth = row[1].trim();
            byte[] jpeg = Files.readAllBytes(dir.resolve(file));
            String b64 = Base64.getEncoder().encodeToString(jpeg);
            String out = CaptchaOcr.solveClean(b64);
            String upper = out == null ? null : out.toUpperCase();

            boolean hit = truth.equals(out);
            boolean hitUpper = truth.equalsIgnoreCase(out);
            if (hit) {
                exact++;
            }
            if (hitUpper) {
                exactUpper++;
            }
            chars += truth.length() == 0 ? 0 : matchesAtPositions(truth, out);
            charsUpper += truth.length() == 0 ? 0 : matchesAtPositions(truth, upper);
            if (!hitUpper) {
                misses.add(file + ": truth=" + truth + " ocr=" + out);
            }
            System.out.println(file + ": truth=" + truth + " ocr=" + out
                    + (hitUpper ? (hit ? " [OK]" : " [OK-case]") : " [MISS]"));
        }

        int total = rows.size();
        int charTotal = 0;
        for (String[] row : rows) {
            charTotal += row[1].trim().length();
        }
        System.out.println();
        System.out.println("backend        : " + CaptchaOcr.activeBackend());
        System.out.println("samples        : " + total
                + "  (" + (System.currentTimeMillis() - startedAt) + " ms total)");
        System.out.println("exact match    : " + exact + "/" + total);
        System.out.println("match upper-cs : " + exactUpper + "/" + total);
        System.out.println("char accuracy  : " + chars + "/" + charTotal
                + "  upper-cs: " + charsUpper + "/" + charTotal);
        if (!misses.isEmpty()) {
            System.out.println();
            System.out.println("mismatches:");
            misses.forEach(m -> System.out.println("  " + m));
        }
    }

    /** Position-wise character matches when lengths line up (0 otherwise). */
    private static int matchesAtPositions(String truth, String out) {
        if (out == null || truth.length() != out.length()) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < truth.length(); i++) {
            if (truth.charAt(i) == out.charAt(i)) {
                n++;
            }
        }
        return n;
    }

    private OcrBenchmark() {
    }
}
