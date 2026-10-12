package com.synapsemcp.ingestion.extraction.parser;

import com.synapsemcp.document.model.FileCategory;
import com.synapsemcp.ingestion.extraction.DocumentExtractionException;
import com.synapsemcp.ingestion.extraction.DocumentParserStrategy;
import com.synapsemcp.ingestion.extraction.ExtractionRequest;
import com.synapsemcp.ingestion.extraction.ExtractionResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/**
 * .xlsx and .xls: every sheet becomes a "## Sheet name" section holding a Markdown table. The first non-empty row is
 * the header. Values are rendered as Excel displays them (dates, percentages, currency) and formulas are evaluated.
 */
@Component
public class PoiExcelDocumentParser implements DocumentParserStrategy {

    @Override
    public boolean supports(FileCategory category) {
        return category == FileCategory.EXCEL;
    }

    @Override
    public ExtractionResult parse(ExtractionRequest request) {
        StringBuilder out = new StringBuilder();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(request.content()))) {
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            DataFormatter formatter = new DataFormatter();
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                List<List<String>> rows = readRows(sheet, formatter, evaluator);
                if (rows.isEmpty()) {
                    continue;
                }
                out.append("## ").append(sheet.getSheetName()).append("\n\n")
                        .append(MarkdownSupport.table(rows)).append('\n');
            }
        } catch (IOException | RuntimeException ex) {
            throw new DocumentExtractionException("Could not read spreadsheet " + request.filename() + ": "
                    + ex.getMessage(), ex);
        }
        return new ExtractionResult(MarkdownSupport.normalize(out.toString()));
    }

    private List<List<String>> readRows(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        List<List<String>> rows = new ArrayList<>();
        for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            for (int c = 0; c < row.getLastCellNum(); c++) {
                cells.add(value(row.getCell(c), formatter, evaluator));
            }
            while (!cells.isEmpty() && cells.get(cells.size() - 1).isBlank()) {
                cells.remove(cells.size() - 1);
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        return rows;
    }

    private String value(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        try {
            return formatter.formatCellValue(cell, evaluator);
        } catch (RuntimeException ex) {
            // Unsupported function or external reference: fall back to the value Excel cached in the file.
            return cell.getCellType() == CellType.FORMULA ? cachedValue(cell, formatter) : "";
        }
    }

    private String cachedValue(Cell cell, DataFormatter formatter) {
        return switch (cell.getCachedFormulaResultType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> formatter.formatRawCellContents(cell.getNumericCellValue(),
                    cell.getCellStyle().getDataFormat(), cell.getCellStyle().getDataFormatString());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }
}
