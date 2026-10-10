package com.moon.moonagent.ai.agent.document;

/**
 * Reserved seam for future scanned-document OCR/vision extraction.
 * The first implementation intentionally leaves this unbound, so image-only
 * or scanned documents return a deterministic no_text result.
 */
public interface VisionDocumentFallback {

    ParsedDocument extract(byte[] bytes, String fileName, String mimeType);
}
