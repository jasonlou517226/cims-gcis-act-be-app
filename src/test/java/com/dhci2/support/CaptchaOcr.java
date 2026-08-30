package com.dhci2.support;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Solves the Online Booking image captcha using the macOS built-in Vision
 * framework via a small Swift CLI (tools/captcha-ocr.swift).
 *
 * The Swift binary is compiled once on demand and cached next to the source.
 * Recognition is best-effort: callers should validate the returned text
 * (e.g. 4 alphanumeric chars) and retry with a fresh captcha on failure.
 */
public final class CaptchaOcr {

    private static final Path TOOL_DIR = Paths.get(System.getProperty("user.dir"), "tools");
    private static final Path SWIFT_SOURCE = TOOL_DIR.resolve("captcha-ocr.swift");
    private static final Path SWIFT_BINARY = TOOL_DIR.resolve("captcha-ocr");
    private static final long TIMEOUT_SECONDS = 30;

    private static boolean compiled = false;

    private CaptchaOcr() {
    }

    /**
     * Runs OCR on a base64-encoded JPEG and returns the recognized text
     * (raw, may contain noise), or {@code null} when recognition fails.
     *
     * @param base64Image raw base64 payload (no data: URI prefix; newlines tolerated)
     */
    public static synchronized String solve(String base64Image) {
        ensureCompiled();
        if (base64Image == null || base64Image.isEmpty()) {
            return null;
        }
        String cleaned = base64Image.replaceAll("\\s+", "");
        try {
            ProcessBuilder pb = new ProcessBuilder(SWIFT_BINARY.toString(), "-");
            pb.redirectErrorStream(false);
            Process process = pb.start();
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(cleaned.getBytes(StandardCharsets.US_ASCII));
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() == 0 ? output : null;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /**
     * Convenience: solves and keeps only alphanumerics.
     * Case is preserved — the captcha comparison is case-sensitive, so the
     * Swift tool (which now also latinizes Cyrillic homoglyphs) is the
     * authority on casing.
     */
    public static String solveClean(String base64Image) {
        String raw = solve(base64Image);
        if (raw == null) {
            return null;
        }
        return raw.replaceAll("[^A-Za-z0-9]", "");
    }

    /** Compiles the Swift tool once; no-op afterwards (also verified on later runs). */
    private static void ensureCompiled() {
        if (compiled && Files.isExecutable(SWIFT_BINARY)) {
            return;
        }
        if (!Files.exists(SWIFT_SOURCE)) {
            throw new IllegalStateException("captcha-ocr.swift not found at " + SWIFT_SOURCE);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("swiftc", "-O",
                    "-o", SWIFT_BINARY.toString(), SWIFT_SOURCE.toString());
            pb.directory(TOOL_DIR.toFile());
            Process p = pb.start();
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("swiftc timed out");
            }
            if (p.exitValue() != 0) {
                throw new IllegalStateException("swiftc failed with exit code " + p.exitValue()
                        + ": " + new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            }
            compiled = true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Failed to compile captcha-ocr.swift", e);
        }
    }
}