package com.gcis.support;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Cross-platform OCR backend that solves the Online Booking image captcha
 * with the {@code tesseract} CLI (Linux containers / CI, Windows local runs).
 *
 * Tuned against a harvested sample corpus (see OcrBenchmark):
 * - charset: 30/30 harvested answers were uppercase letters + digits with no
 *   lowercase, no '0' and no '1' (anti-ambiguity charset). Output is
 *   therefore upper-cased and '0' is mapped to 'O' — strictly reducing the
 *   error space. A character whitelist is NOT used: with the LSTM engine a
 *   whitelist suppresses output entirely when the top choice is filtered
 *   (measured: whole glyphs read as empty).
 * - preprocessing: upscale + (Otsu | Sauvola) binarization variants; each is
 *   read with psm 8/13, dictionaries disabled (random text must not be
 *   "corrected" into words); answers with the ideal 4-char shape win by
 *   consensus voting.
 * - measured ceiling on this distorted captcha is ~20% per-image exact match
 *   (fast AND best LSTM models); the login test therefore treats OCR as a
 *   best-effort fallback and retries with fresh images.
 * - LSTM model: point -Docr.tessdata=<dir> at a directory holding an
 *   alternative eng.traineddata to A/B models.
 */
public final class TesseractOcr {

    private static final long TIMEOUT_SECONDS = 25;

    /**
     * Page segmentation modes to query per variant. Measured on live samples:
     * 8 (single word) and 13 (raw line, no layout analysis) both recover the
     * 4 chars where the line-oriented 7 mostly returns nothing.
     */
    private static final int[] PSM_MODES = {8, 13};

    /** Cyrillic homoglyphs → visually identical Latin letters. */
    private static final Map<Character, Character> LATINIZE = Map.ofEntries(
            Map.entry('А', 'A'), Map.entry('В', 'B'), Map.entry('Е', 'E'),
            Map.entry('К', 'K'), Map.entry('М', 'M'), Map.entry('Н', 'H'),
            Map.entry('О', 'O'), Map.entry('Р', 'P'), Map.entry('С', 'C'),
            Map.entry('Т', 'T'), Map.entry('У', 'Y'), Map.entry('Х', 'X'),
            Map.entry('а', 'a'), Map.entry('с', 'c'), Map.entry('е', 'e'),
            Map.entry('о', 'o'), Map.entry('р', 'p'), Map.entry('х', 'x'));

    /** Cached availability (probing spawns a process; avoid it per captcha). */
    private static volatile Boolean available;

    /** Optional alternative tessdata directory (-Docr.tessdata=...). */
    private static String tessDataDir() {
        String dir = System.getProperty("ocr.tessdata", "").trim();
        if (dir.isEmpty()) {
            return null;
        }
        return new File(dir).isDirectory() ? dir : null;
    }

    private TesseractOcr() {
    }

    /** True when the {@code tesseract} CLI is on the PATH (probed once). */
    public static boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        boolean ok = probeTesseract();
        available = ok;
        return ok;
    }

    private static boolean probeTesseract() {
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
     * candidate found (upper-cased), or {@code null} on failure. Callers
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
            String longest = null; // non-4-char fallback (caller retries)
            Map<String, long[]> votes = new LinkedHashMap<>(); // candidate -> {count, variantBitmask}
            int variantIdx = 0;
            for (BufferedImage variant : buildVariants(img)) {
                for (int psm : PSM_MODES) {
                    String candidate = clean(runTesseract(toPng(variant), psm));
                    if (candidate == null) {
                        continue;
                    }
                    if (candidate.length() == 4) {
                        long[] tally = votes.computeIfAbsent(candidate, k -> new long[2]);
                        tally[0]++;
                        tally[1] |= 1L << variantIdx;
                    } else if (longest == null || candidate.length() > longest.length()) {
                        longest = candidate;
                    }
                }
                variantIdx++;
            }
            // Consensus: most votes; ties → more distinct variants; → earliest.
            String winner = null;
            long winnerVotes = 0;
            long winnerVariants = 0;
            for (Map.Entry<String, long[]> e : votes.entrySet()) {
                long count = e.getValue()[0];
                long variants = Long.bitCount(e.getValue()[1]);
                if (count > winnerVotes || (count == winnerVotes && variants > winnerVariants)) {
                    winner = e.getKey();
                    winnerVotes = count;
                    winnerVariants = variants;
                }
            }
            return winner != null ? winner : longest;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /**
     * Pipes a PNG payload to tesseract and returns its raw stdout. Captcha
     * tuning: dictionaries off (random text), no auto-inversion (binarization
     * variants handle polarity explicitly). NOTE: no character whitelist —
     * with the LSTM engine the whitelist suppresses output entirely when the
     * top choice is filtered; charset constraints are enforced post-hoc in
     * {@link #clean(String)}.
     */
    private static String runTesseract(byte[] png, int psm) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("tesseract", "stdin", "stdout",
                "--dpi", "300", "--psm", String.valueOf(psm)));
        String tessdata = tessDataDir();
        if (tessdata != null) {
            cmd.add("--tessdata-dir");
            cmd.add(tessdata);
        }
        cmd.add("-c");
        cmd.add("load_system_dawg=0");
        cmd.add("-c");
        cmd.add("load_freq_dawg=0");
        cmd.add("-c");
        cmd.add("tessedit_do_invert=0");
        ProcessBuilder pb = new ProcessBuilder(cmd);
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

    /**
     * Pre-processing variants. The active set is selected with
     * -Docr.variants=new (default) | classic — kept switchable so accuracy
     * can be re-measured on a corpus (see OcrBenchmark). Trim/pad and
     * per-character segmentation variants were measured harmful or neutral
     * on the corpus and are intentionally absent.
     */
    private static List<BufferedImage> buildVariants(BufferedImage src) {
        List<BufferedImage> variants = new ArrayList<>();
        if ("classic".equalsIgnoreCase(System.getProperty("ocr.variants", "new").trim())) {
            variants.add(otsu(upscale(src, 4, true), false));
            variants.add(otsu(upscale(src, 4, true), true));
            variants.add(grayscale(upscale(src, 3, true)));
            variants.add(grayscale(upscale(src, 5, false)));
            variants.add(grayscale(upscale(src, 2, true)));
        } else {
            variants.add(otsu(upscale(src, 4, true), false));
            variants.add(otsu(upscale(src, 4, true), true));
            variants.add(sauvola(upscale(src, 4, true)));
            variants.add(grayscale(upscale(src, 3, true)));
        }
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

    /**
     * Grayscale + global Otsu threshold → black & white image. When
     * {@code autoInvert} is set the polarity is corrected so the text is dark
     * on a light background (tesseract's expectation).
     */
    private static BufferedImage otsu(BufferedImage img, boolean autoInvert) {
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
        boolean invert = false;
        if (autoInvert) {
            int dark = 0;
            for (int t = 0; t <= threshold; t++) {
                dark += hist[t];
            }
            invert = dark > total / 2; // dark background → flip polarity
        }
        return applyThreshold(img, threshold, invert);
    }

    /**
     * Sauvola adaptive thresholding (integral-image based). Unlike global
     * Otsu it copes with gradient / unevenly lit backgrounds, which is where
     * Otsu loses strokes on this captcha.
     */
    private static BufferedImage sauvola(BufferedImage img) {
        grayscale(img);
        int w = img.getWidth();
        int h = img.getHeight();
        int win = Math.max(15, Math.min(w, h) / 6);
        if (win % 2 == 0) {
            win++;
        }
        int half = win / 2;
        double[][] integral = new double[w + 1][h + 1];
        double[][] integralSq = new double[w + 1][h + 1];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = (img.getRGB(x, y) >> 16) & 0xFF;
                integral[x + 1][y + 1] = v + integral[x][y + 1] + integral[x + 1][y] - integral[x][y];
                integralSq[x + 1][y + 1] = (double) v * v + integralSq[x][y + 1] + integralSq[x + 1][y] - integralSq[x][y];
            }
        }
        final double k = 0.34;
        final double r = 128.0;
        int darkCount = 0;
        int[] outLum = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - half);
                int x1 = Math.min(w - 1, x + half);
                int y0 = Math.max(0, y - half);
                int y1 = Math.min(h - 1, y + half);
                int count = (x1 - x0 + 1) * (y1 - y0 + 1);
                double sum = integral[x1 + 1][y1 + 1] - integral[x0][y1 + 1] - integral[x1 + 1][y0] + integral[x0][y0];
                double sumSq = integralSq[x1 + 1][y1 + 1] - integralSq[x0][y1 + 1] - integralSq[x1 + 1][y0] + integralSq[x0][y0];
                double mean = sum / count;
                double variance = Math.max(0.0, sumSq / count - mean * mean);
                double std = Math.sqrt(variance);
                double threshold = mean * (1.0 + k * (std / r - 1.0));
                int lum = ((img.getRGB(x, y) >> 16) & 0xFF) <= threshold ? 0 : 255;
                outLum[y * w + x] = lum;
                if (lum == 0) {
                    darkCount++;
                }
            }
        }
        // Polarity: tesseract wants dark text on a light background.
        boolean invert = darkCount > (w * h) / 2;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = outLum[y * w + x];
                if (invert) {
                    v = 255 - v;
                }
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        return img;
    }

    private static BufferedImage applyThreshold(BufferedImage img, int threshold, boolean invert) {
        int w = img.getWidth();
        int h = img.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int lum = (img.getRGB(x, y) >> 16) & 0xFF;
                int v = lum <= threshold ? 0 : 255;
                if (invert) {
                    v = 255 - v;
                }
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

    /**
     * Latinizes homoglyphs, keeps alphanumerics only, upper-cases the result
     * and maps '0'→'O' ('0' never appears in the captcha charset — 30/30
     * harvested answers — so the mapping strictly reduces the error space).
     */
    private static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (c == '0') {
                c = 'O';
            }
            Character latin = LATINIZE.get(c);
            if (latin != null) {
                c = latin;
            }
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        return sb.isEmpty() ? null : sb.toString().toUpperCase(Locale.ROOT);
    }
}
