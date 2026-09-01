package com.dhci2.support;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * macOS-only OCR backend built on the system Vision framework via a small
 * Swift CLI (tools/captcha-ocr.swift). Kept for local macOS testing; on
 * Linux the {@link TesseractOcr} backend is used instead.
 *
 * The Swift binary is compiled once on demand and cached next to the source.
 * Recognition is best-effort: callers should validate the returned text
 * (e.g. 4 alphanumeric chars) and retry with a fresh captcha on failure.
 */
public final class VisionOcr {

    private static final Path TOOL_DIR = Paths.get(System.getProperty("user.dir"), "tools");
    private static final Path SWIFT_SOURCE = TOOL_DIR.resolve("captcha-ocr.swift");
    private static final Path SWIFT_BINARY = TOOL_DIR.resolve("captcha-ocr");
    private static final long TIMEOUT_SECONDS = 30;

    private static boolean compiled = false;

    private VisionOcr() {
    }

    /** True on macOS with the Swift source present. */
    public static boolean isAvailable() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac")
                && Files.exists(SWIFT_SOURCE);
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