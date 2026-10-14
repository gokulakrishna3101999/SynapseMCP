package com.synapsemcp.ingestion.chunking;

import com.synapsemcp.document.model.FileCategory;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * One chunk per ~128-384 words (design.md section 4.1), splitting only on row boundaries within a sheet
 * so a table row is never cut in half, with the sheet name and the column header row injected into every
 * chunk - including continuation chunks - so each chunk is independently interpretable.
 * {@code PoiExcelDocumentParser} always emits {@code "## <sheet name>"} followed by a Markdown table
 * (header row, then a {@code |---|} separator, then data rows), which is what this parses.
 */
@Component
public class ExcelChunkingStrategy implements ChunkingStrategy {

    static final int TARGET_WORDS = 256;
    private static final Pattern SHEET_BOUNDARY = Pattern.compile("(?m)(?=^## )");
    private static final Pattern TABLE_SEPARATOR_ROW = Pattern.compile("^\\|(\\s*-+\\s*\\|)+$");

    @Override
    public boolean supports(FileCategory category) {
        return category == FileCategory.EXCEL;
    }

    @Override
    public List<ChunkPayload> chunk(String markdown, FileCategory category) {
        List<String> chunks = new ArrayList<>();
        for (String sheet : SHEET_BOUNDARY.split(markdown)) {
            String trimmed = sheet.strip();
            if (!trimmed.isEmpty()) {
                chunks.addAll(chunkSheet(trimmed));
            }
        }
        if (chunks.isEmpty()) {
            chunks.add(markdown.strip());
        }
        return ChunkingSupport.toPayloads(chunks);
    }

    private static List<String> chunkSheet(String sheet) {
        String[] lines = sheet.split("\n");
        String heading = lines[0];
        List<String> tableLines = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                tableLines.add(lines[i]);
            }
        }
        if (tableLines.isEmpty()) {
            return List.of(heading);
        }

        String headerRow = tableLines.get(0);
        boolean hasSeparator = tableLines.size() > 1 && TABLE_SEPARATOR_ROW.matcher(tableLines.get(1)).matches();
        String separatorRow = hasSeparator ? tableLines.get(1) : null;
        List<String> dataRows = tableLines.subList(hasSeparator ? 2 : 1, tableLines.size());

        StringBuilder prefixBuilder = new StringBuilder(heading).append("\n\n").append(headerRow).append('\n');
        if (separatorRow != null) {
            prefixBuilder.append(separatorRow).append('\n');
        }
        String prefix = prefixBuilder.toString();
        int prefixWords = ChunkingSupport.wordCount(prefix);

        if (dataRows.isEmpty()) {
            return List.of(prefix.strip());
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        int bodyWords = 0;
        for (String row : dataRows) {
            int rowWords = ChunkingSupport.wordCount(row);
            if (bodyWords > 0 && prefixWords + bodyWords + rowWords > TARGET_WORDS) {
                chunks.add(prefix + body);
                body.setLength(0);
                bodyWords = 0;
            }
            body.append(row).append('\n');
            bodyWords += rowWords;
        }
        if (bodyWords > 0) {
            chunks.add(prefix + body);
        }
        return chunks;
    }
}
