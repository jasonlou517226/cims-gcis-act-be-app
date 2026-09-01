package com.dhci2.support;

/**
 * Facade that picks the best available OCR backend for the current platform:
 *
 * - macOS  → {@link VisionOcr} (system Vision framework via tools/captcha-ocr.swift)
 * - Linux  → {@link TesseractOcr} (tesseract CLI; used in Docker / AWS / CI)
 *
 * Override the auto-detection with -Docr.engine=vision or -Docr.engine=tesseract
 * (e.g. to benchmark both backends on macOS). When the selected backend is not
 * available the other one is tried as a fallback.
 */
public final class CaptchaOcr {

    /** Lazily resolved backend name (vision / tesseract) for logging. */
    private static volatile String activeBackend;

    private CaptchaOcr() {
    }

    /** Name of the backend used for the last solve (diagnostics). */
    public static String activeBackend() {
        return activeBackend;
    }

    /**
     * Runs OCR on a base64-encoded JPEG (no data: URI prefix) and returns the
     * recognized text, or {@code null} on failure. Case is preserved — the
     * captcha comparison is case-sensitive; backends also latinize Cyrillic
     * homoglyphs.
     */
    public static String solve(String base64Image) {
        String engine = System.getProperty("ocr.engine", "auto").trim().toLowerCase();
        // Auto: macOS prefers Vision, everything else uses Tesseract.
        boolean preferVision = !"tesseract".equals(engine)
                && ("vision".equals(engine) || VisionOcr.isAvailable());
        return solveWith(base64Image, preferVision);
    }

    private static String solveWith(String base64Image, boolean preferVision) {
        if (preferVision && VisionOcr.isAvailable()) {
            activeBackend = "vision";
            return VisionOcr.solve(base64Image);
        }
        if (!preferVision && TesseractOcr.isAvailable()) {
            activeBackend = "tesseract";
            return TesseractOcr.solve(base64Image);
        }
        // Requested backend unavailable → try the other one before giving up.
        System.out.println("[CaptchaOcr] preferred backend unavailable; trying the other one");
        if (preferVision && TesseractOcr.isAvailable()) {
            activeBackend = "tesseract";
            return TesseractOcr.solve(base64Image);
        }
        if (!preferVision && VisionOcr.isAvailable()) {
            activeBackend = "vision";
            return VisionOcr.solve(base64Image);
        }
        return null;
    }

    /**
     * Convenience: solves and keeps only alphanumerics.
     * Case is preserved — the captcha comparison is case-sensitive, so the
     * OCR backends (which also latinize Cyrillic homoglyphs) are the
     * authority on casing.
     */
    public static String solveClean(String base64Image) {
        String raw = solve(base64Image);
        if (raw == null) {
            return null;
        }
        return raw.replaceAll("[^A-Za-z0-9]", "");
    }
}