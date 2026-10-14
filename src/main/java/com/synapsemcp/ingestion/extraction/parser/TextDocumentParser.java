package com.synapsemcp.ingestion.extraction.parser;

import com.synapsemcp.document.model.FileCategory;
import com.synapsemcp.ingestion.extraction.DocumentExtractionException;
import com.synapsemcp.ingestion.extraction.DocumentParserStrategy;
import com.synapsemcp.ingestion.extraction.ExtractionRequest;
import com.synapsemcp.ingestion.extraction.ExtractionResult;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * .txt, .csv, .md and .json are read as-is (no LLM, no restructuring). UTF-8 is expected; a UTF-8/UTF-16 byte-order
 * mark is honoured, and bytes that are not valid UTF-8 are decoded as Windows-1252 (the usual encoding of legacy
 * CSV exports) rather than failing.
 */
@Component
public class TextDocumentParser implements DocumentParserStrategy {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    @Override
    public boolean supports(FileCategory category) {
        return category == FileCategory.TEXT;
    }

    @Override
    public ExtractionResult parse(ExtractionRequest request) {
        String text = decode(request.content());
        if (text.indexOf('\u0000') >= 0) {
            throw new DocumentExtractionException("File does not look like text (contains NUL bytes): " + request.filename());
        }
        return new ExtractionResult(MarkdownSupport.normalize(text));
    }

    static String decode(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            return new String(bytes, WINDOWS_1252);
        }
    }
}
