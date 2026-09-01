package com.dhci2.support;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Cross-platform OCR backend that solves the Online Booking image captcha
 * with the {@code tesseract} CLI (Linux containers / CI).
 *
 * The raw captcha JPEG is pre-processed in pure Java (upscale + grayscale +
 * Otsu binarization) into several variants; each variant is piped to
 * {@code tesseract stdin stdout --psm 7} with an alphanumeric whitelist.
 * The first variant producing a 4-char alphanumeric answer wins (the captcha
 * is case-sensitive, so case is preserved).
 *
 * Cyrillic homoglyphs occasionally appear in the captcha; they are mapped to
 * their visually identical Latin counterparts (same behavior as the macOS
 * Vision backend in {@link VisionOcr}).
 */
public final class TesseractOcr {

    private static final long TIMEOUT_SECONDS = 25;

    /** Only these characters can appear in a captcha answer. */
    private static final String WHITELIST =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    /** Cyrillic homoglyphs → visually identical Latin letters. */
    private static final Map<Character, Character> LATINIZE = Map.ofEntries(
            Map.entry('А', 'A'), Map.entry('В', 'B'), Map.entry('Е', 'E'),
            Map.entry('К', 'K'), Map.entry('М', 'M'), Map.entry('Н', 'H'),
            Map.entry('О', 'O'), Map.entry('Р', 'P'), Map.entry('С', 'C'),
            Map.entry('Т', 'T'), Map.entry('У', 'Y'), Map.entry('Х', 'X'),
            Map.entry('а', 'a'), Map.entry('с', 'c'), Map.entry('е', 'e'),
            Map.entry('о', 'o'), Map.entry('р', 'p'), Map.entry('х', 'x'));

    private TesseractOcr() {
    }

    /** True when the {@code tesseract} CLI is on the PATH. */
    public static boolean isAvailable() {
        try {
            Process p = new ProcessBuilder("tesseract", "--version")
                    .redirectErrorStream(true)
                    .start();
            // Drain output to avoid blocking, then check the exit code.
            p.getInputStream().readAllBytes();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /**
     * Runs OCR on a base64-encoded JPEG and returns the best alphanumeric
     * candidate found (case preserved), or {@code null} on failure. Callers
     * should validate the length (captcha answers are 4 chars) and retry with
     * a fresh captcha when in doubt.
     */
    public static synchronized String solve(String base64Image) {
        if (base64Image == null || base64Image.isEmpty()) {
            return null;
        }
        try {
            byte[] jpeg = Base64.getDecoder().decode(base64Image.replaceAll("\\s+", ""));
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (img == null) {
                return null;
            }
            String best = null;
            for (byte[] variant : buildVariants(img)) {
                String candidate = clean(runTesseract(variant));
                if (candidate == null) {
                    continue;
                }
                if (candidate.length() == 4) {
                    return candidate; // exact-length answer wins immediately
                }
                if (best == null || candidate.length() > best.length()) {
                    best = candidate;
                }
            }
            return best;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /** Pipes a PNG payload to tesseract and returns its raw stdout. */
    private static String runTesseract(byte[] png) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("tesseract", "stdin", "stdout",
                "--dpi", "300", "--psm", "7",
                "-c", "tessedit_char_whitelist=" + WHITELIST);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        try (OutputStream stdin = p.getOutputStream()) {
            stdin.write(png);
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return null;
        }
        return p.exitValue() == 0 ? out : null;
    }

    /** Pre-processing variants, ordered cheapest → most aggressive. */
    private static List<byte[]> buildVariants(BufferedImage src) throws IOException {
        List<byte[]> variants = new ArrayList<>();
        variants.add(toPng(grayscale(upscale(src, 3, true))));   // smooth 3x
        variants.add(toPng(otsu(upscale(src, 4, true))));        // smooth 4x + binarized
        variants.add(toPng(grayscale(upscale(src, 5, false))));  // hard 5x pixels
        return variants;
    }

    private static BufferedImage upscale(BufferedImage src, int factor, boolean smooth) {
        int w = src.getWidth() * factor;
        int h = src.getHeight() * factor;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, smooth
                ? RenderingHints.VALUE_INTERPOLATION_BILINEAR
                : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static BufferedImage grayscale(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int lum = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                img.setRGB(x, y, (lum << 16) | (lum << 8) | lum);
            }
        }
        return img;
    }

    /** Grayscale + global Otsu threshold → black & white image. */
    private static BufferedImage otsu(BufferedImage img) {
        grayscale(img);
        int w = img.getWidth();
        int h = img.getHeight();
        int total = w * h;
        int[] hist = new int[256];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                hist[(img.getRGB(x, y) >> 16) & 0xFF]++;
            }
        }
        long sum = 0;
        for (int t = 0; t < 256; t++) {
            sum += (long) t * hist[t];
        }
        long sumB = 0;
        int wB = 0;
        double maxVar = -1;
        int threshold = 127;
        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) {
                continue;
            }
            int wF = total - wB;
            if (wF == 0) {
                break;
            }
            sumB += (long) t * hist[t];
            double mB = (double) sumB / wB;
            double mF = (double) (sum - sumB) / wF;
            double between = (double) wB * wF * (mB - mF) * (mB - mF);
            if (between > maxVar) {
                maxVar = between;
                threshold = t;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int lum = (img.getRGB(x, y) >> 16) & 0xFF;
                int v = lum <= threshold ? 0 : 255;
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        return img;
    }

    private static byte[] toPng(BufferedImage img) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    /** Latinizes homoglyphs and keeps alphanumerics only (case preserved). */
    private static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            Character latin = LATINIZE.get(c);
            if (latin != null) {
                c = latin;
            }
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}