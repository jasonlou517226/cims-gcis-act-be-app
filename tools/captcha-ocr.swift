#!/usr/bin/env swift
//
// captcha-ocr.swift — CAPTCHA OCR CLI for dhci2_auto_check
//
// Uses the macOS built-in Vision framework (VNRecognizeTextRequest) to
// recognize the 4-character text inside a captcha JPEG image.
//
// Strategy: OCR several rendered variants of the source image
// (1x–4x upscale; accurate + fast recognition), normalize Cyrillic homoglyphs
// back to Latin, and pick the answer by CONSENSUS among the variants that
// read 4 alphanumeric chars. Case is PRESERVED — the backend comparison is
// case-sensitive.
//
// Usage:
//   captcha-ocr <image-file>          # recognize text in the image file
//   echo <base64> | captcha-ocr -     # recognize text from base64 on stdin
//
// Output: best candidate on stdout; all candidates on stderr (debug).
// Exit code 0 on success, non-zero on load failure.
//
// Build (done automatically by the Java CaptchaSolver if needed):
//   swiftc -O -o captcha-ocr captcha-ocr.swift
//
import Foundation
import Vision
import ImageIO
import CoreGraphics

func loadCGImage(_ path: String) -> CGImage? {
    // ImageIO works reliably in CLI tools (no AppKit required).
    if path == "-" {
        var raw = FileHandle.standardInput.readDataToEndOfFile()
        // The server emits MIME-style base64 without trailing padding;
        // strip whitespace and pad to a multiple of 4 so Data can decode it.
        let ws = Set([UInt8(ascii: " "), UInt8(ascii: "\n"), UInt8(ascii: "\r"), UInt8(ascii: "\t")])
        raw = Data(raw.filter { !ws.contains($0) })
        while raw.count % 4 != 0 {
            raw.append(UInt8(ascii: "="))
        }
        guard let decoded = Data(base64Encoded: raw) else { return nil }
        guard let src = CGImageSourceCreateWithData(decoded as CFData, nil) else { return nil }
        return CGImageSourceCreateImageAtIndex(src, 0, nil)
    }
    let url = URL(fileURLWithPath: path) as CFURL
    guard let src = CGImageSourceCreateWithURL(url, nil) else { return nil }
    return CGImageSourceCreateImageAtIndex(src, 0, nil)
}

/// Upscale (or copy) a CGImage by an integer factor using CoreGraphics.
func scaled(_ image: CGImage, by factor: Int) -> CGImage? {
    guard factor > 1 else { return image }
    let w = image.width * factor
    let h = image.height * factor
    let cs = CGColorSpaceCreateDeviceRGB()
    guard let ctx = CGContext(data: nil, width: w, height: h,
                              bitsPerComponent: 8, bytesPerRow: 0,
                              space: cs,
                              bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
    ctx.interpolationQuality = .none // nearest-neighbor keeps strokes crisp
    ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
    return ctx.makeImage()
}

/// Run one OCR pass and return the raw joined string.
func ocr(_ cg: CGImage, level: VNRequestTextRecognitionLevel, minTextHeight: Float) -> String {
    var result = ""
    let request = VNRecognizeTextRequest { req, _ in
        if let observations = req.results as? [VNRecognizedTextObservation] {
            result = observations
                .compactMap { $0.topCandidates(1).first?.string }
                .joined(separator: "")
        }
    }
    request.recognitionLevel = level
    request.usesLanguageCorrection = false
    request.recognitionLanguages = ["en-US"]
    request.minimumTextHeight = minTextHeight
    let handler = VNImageRequestHandler(cgImage: cg, options: [:])
    try? handler.perform([request])
    return result
}

/// Map Cyrillic homoglyphs Vision sometimes emits back to their Latin
/// lookalikes (А→A, м→m, ...), preserving case. Everything else untouched.
func latinize(_ s: String) -> String {
    let map: [Character: Character] = [
        "А": "A", "В": "B", "С": "C", "Е": "E", "Н": "H", "К": "K", "М": "M",
        "О": "O", "Р": "P", "Т": "T", "Х": "X", "У": "Y", "И": "N",
        "а": "a", "в": "b", "с": "c", "е": "e", "о": "o", "р": "p",
        "х": "x", "у": "y", "м": "m", "н": "h", "к": "k", "т": "t", "и": "n",
    ]
    return String(s.map { map[$0] ?? $0 })
}

/// Keep A-Z0-9 only (captcha alphabet), case preserved — the backend
/// comparison is case-sensitive, so uppercasing would corrupt the answer.
func normalize(_ s: String) -> String {
    String(latinize(s).filter { c in
        (c.isLetter && c.isASCII) || (c.isNumber && c.isASCII)
    })
}

/// Score a candidate: 4 alphanumeric chars is the ideal captcha shape.
func score(_ s: String) -> Int {
    if s.count == 4 { return 100 }
    if s.count == 3 || s.count == 5 { return 60 }
    if s.count == 2 || s.count == 6 { return 30 }
    return s.count == 0 ? -1 : 10
}

/// Pick the winning candidate:
/// 1. Among perfect-shape (4-char) candidates, vote — the string the most
///    variants agree on wins (ties broken by earliest appearance).
/// 2. If no 4-char candidate exists, fall back to the highest score.
func pickBest() -> String? {
    let perfectOrder = candidates.filter { $0.1 == 100 }.map { $0.0 }
    if !perfectOrder.isEmpty {
        var counts: [String: Int] = [:]
        perfectOrder.forEach { counts[$0, default: 0] += 1 }
        if let winner = counts.max(by: { a, b in
            if a.value != b.value { return a.value < b.value }
            return perfectOrder.firstIndex(of: a.key)! > perfectOrder.firstIndex(of: b.key)!
        }) {
            FileHandle.standardError.write(
                "[vote] \(winner.key) x\(winner.value)/\(perfectOrder.count)\n".data(using: .utf8)!)
            return winner.key
        }
    }
    return candidates.max(by: { $0.1 < $1.1 })?.0
}

let args = CommandLine.arguments
guard args.count >= 2 else {
    FileHandle.standardError.write("usage: captcha-ocr <image-file|->\n".data(using: .utf8)!)
    exit(2)
}
guard let cg = loadCGImage(args[1]) else {
    FileHandle.standardError.write("failed to load image\n".data(using: .utf8)!)
    exit(1)
}

var candidates: [(String, Int)] = [] // (normalized, score)
func consider(_ raw: String, tag: String) {
    let n = normalize(raw)
    candidates.append((n, score(n)))
    FileHandle.standardError.write("[\(tag)] raw=\(raw) norm=\(n)\n".data(using: .utf8)!)
}

// Multi-variant OCR: scale x level
for factor in [1, 2, 3, 4] {
    guard let v = scaled(cg, by: factor) else { continue }
    consider(ocr(v, level: .accurate, minTextHeight: 0), tag: "accurate-\(factor)x")
    consider(ocr(v, level: .fast, minTextHeight: 0), tag: "fast-\(factor)x")
}

guard let best = pickBest() else {
    print("")
    exit(0)
}
print(best)
