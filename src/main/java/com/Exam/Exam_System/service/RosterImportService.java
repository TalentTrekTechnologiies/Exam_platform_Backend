package com.Exam.Exam_System.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.Exam.Exam_System.util.CsvParser;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Reading a candidate roster out of whatever the college actually has.
 *
 * Colleges keep student lists in Excel far more often than anything else, then
 * Word tables, then PDF, and only rarely CSV. Insisting on CSV pushes the
 * conversion work onto exam staff — and a hand-converted roster is where
 * mistyped hall tickets come from. Every format lands in the same preview, and
 * nothing is saved until someone has looked at it.
 */
@Service
public class RosterImportService {

    private static final Logger log = LoggerFactory.getLogger(RosterImportService.class);

    /** Header words that identify the hall-ticket column, in any casing. */
    private static final Set<String> TICKET_HEADERS = Set.of(
            "hallticket", "hall ticket", "hall ticket no", "hall ticket number",
            "rollno", "roll no", "roll number", "rollnumber", "registerno",
            "register no", "register number", "regno", "reg no", "admissionno",
            "admission no", "id", "student id", "htno", "ht no");

    /** Header words that identify the name column. */
    private static final Set<String> NAME_HEADERS = Set.of(
            "name", "student name", "studentname", "candidate", "candidate name",
            "full name", "fullname", "student");

    /** A hall ticket is dense and alphanumeric; a name is not. */
    private static final Pattern LOOKS_LIKE_TICKET = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9/_-]{2,29}$");

    /** One roster row, as proposed. */
    public record RosterRow(String hallTicket, String name, String issue) {
        public boolean isUsable() { return issue == null; }
    }

    public record RosterPreview(
            List<RosterRow> rows,
            int usableCount,
            int problemCount,
            List<String> warnings,
            String sourceFileName) {}

    public RosterPreview parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file was uploaded.");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);

        List<List<String>> table;
        try {
            if (name.endsWith(".csv") || name.endsWith(".txt")) {
                table = fromCsv(file);
            } else if (name.endsWith(".xlsx") || name.endsWith(".xlsm")) {
                table = fromExcel(file);
            } else if (name.endsWith(".xls")) {
                throw new IllegalArgumentException(
                        "The old .xls format cannot be read. Save it as .xlsx and try again.");
            } else if (name.endsWith(".docx")) {
                table = fromWord(file);
            } else if (name.endsWith(".pdf")) {
                table = fromPdf(file);
            } else {
                throw new IllegalArgumentException(
                        "Upload a CSV, Excel (.xlsx), Word (.docx) or PDF file.");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("That file could not be opened: " + e.getMessage());
        }

        return build(table, file.getOriginalFilename());
    }

    // ── Format readers ───────────────────────────────────────────────────────

    private List<List<String>> fromCsv(MultipartFile file) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        for (String line : new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\r?\\n")) {
            List<String> fields = CsvParser.parseLine(line);
            if (!CsvParser.isBlank(fields)) rows.add(fields);
        }
        return rows;
    }

    private List<List<String>> fromExcel(MultipartFile file) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            // Only the first sheet: later sheets are usually notes, summaries or
            // last year's list, and silently importing them would be worse than
            // ignoring them.
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    // Formatted, not raw: a roll number stored as a number must
                    // read as 24001, never 24001.0.
                    cells.add(formatter.formatCellValue(row.getCell(c)).trim());
                }
                if (cells.stream().anyMatch(s -> !s.isBlank())) rows.add(cells);
            }
        }
        return rows;
    }

    private List<List<String>> fromWord(MultipartFile file) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(file.getInputStream())) {
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    List<String> cells = new ArrayList<>();
                    row.getTableCells().forEach(c -> cells.add(c.getText().trim()));
                    if (cells.stream().anyMatch(s -> !s.isBlank())) rows.add(cells);
                }
            }
            // No table: fall back to reading lines, which handles the common
            // "hall ticket then name" list typed straight into the document.
            if (rows.isEmpty()) {
                String text = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(doc).getText();
                for (String line : text.split("\\r?\\n")) {
                    List<String> parts = splitLooseLine(line);
                    if (!parts.isEmpty()) rows.add(parts);
                }
            }
        }
        return rows;
    }

    private List<List<String>> fromPdf(MultipartFile file) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(file.getBytes())) {
            String text = new PDFTextStripper().getText(doc);
            for (String line : text.split("\\r?\\n")) {
                List<String> parts = splitLooseLine(line);
                if (!parts.isEmpty()) rows.add(parts);
            }
        }
        return rows;
    }

    /** Splits a plain text line on commas, tabs, or runs of spaces. */
    private List<String> splitLooseLine(String line) {
        String trimmed = line == null ? "" : line.strip();
        if (trimmed.isEmpty()) return List.of();

        String[] parts = trimmed.contains(",") ? trimmed.split(",")
                : trimmed.contains("\t") ? trimmed.split("\t")
                : trimmed.split("\\s{2,}");

        List<String> cells = new ArrayList<>();
        for (String p : parts) if (!p.isBlank()) cells.add(p.strip());

        // A single-space-separated line like "24CSE001 Asha Rao" is still a
        // valid roster row; split it at the first space after the ticket.
        if (cells.size() == 1 && trimmed.contains(" ")) {
            int space = trimmed.indexOf(' ');
            String head = trimmed.substring(0, space).strip();
            String tail = trimmed.substring(space + 1).strip();
            if (LOOKS_LIKE_TICKET.matcher(head).matches() && !tail.isBlank()) {
                return List.of(head, tail);
            }
        }
        return cells;
    }

    // ── Interpretation ───────────────────────────────────────────────────────

    private RosterPreview build(List<List<String>> table, String fileName) {
        List<RosterRow> rows = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (table.isEmpty()) {
            throw new IllegalArgumentException(
                    "No rows could be read from that file. If it is a scan, the page is an image "
                            + "and would need optical character recognition, which this import does not do.");
        }

        int ticketCol = 0;
        int nameCol = 1;
        int startRow = 0;

        // Prefer explicit headers; fall back to shape when there are none.
        List<String> first = table.get(0);
        int headerTicket = indexOfHeader(first, TICKET_HEADERS);
        int headerName = indexOfHeader(first, NAME_HEADERS);

        if (headerTicket >= 0 && headerName >= 0) {
            ticketCol = headerTicket;
            nameCol = headerName;
            startRow = 1;
        } else if (first.size() >= 2) {
            // No headers: whichever column looks like an identifier is the ticket.
            if (!LOOKS_LIKE_TICKET.matcher(first.get(0)).matches()
                    && LOOKS_LIKE_TICKET.matcher(first.get(1)).matches()) {
                ticketCol = 1;
                nameCol = 0;
                warnings.add("Columns appear to be name first, then hall ticket — check the preview.");
            }
        }

        Set<String> seen = new HashSet<>();
        for (int i = startRow; i < table.size(); i++) {
            List<String> cells = table.get(i);
            String ticket = cell(cells, ticketCol);
            String name = cell(cells, nameCol);

            if (ticket.isBlank() && name.isBlank()) continue;

            String issue = null;
            if (ticket.isBlank()) {
                issue = "No hall ticket in this row.";
            } else if (name.isBlank()) {
                issue = "No name in this row.";
            } else if (!LOOKS_LIKE_TICKET.matcher(ticket).matches()) {
                issue = "That does not look like a hall ticket — check the columns.";
            } else if (!seen.add(ticket.toUpperCase(Locale.ROOT))) {
                // A duplicate inside one file is almost always a copy-paste slip,
                // and would otherwise silently enrol one candidate twice.
                issue = "Duplicate hall ticket in this file.";
            }

            rows.add(new RosterRow(ticket, name, issue));
        }

        int usable = (int) rows.stream().filter(RosterRow::isUsable).count();
        int problems = rows.size() - usable;

        if (usable == 0) {
            warnings.add("No usable rows were found. The file should have a hall ticket "
                    + "and a candidate name in each row.");
        }

        log.info("Parsed roster from {} — {} usable, {} needing attention.", fileName, usable, problems);
        return new RosterPreview(rows, usable, problems, warnings, fileName);
    }

    private int indexOfHeader(List<String> header, Set<String> candidates) {
        for (int i = 0; i < header.size(); i++) {
            String value = header.get(i) == null ? "" : header.get(i).trim().toLowerCase(Locale.ROOT);
            if (candidates.contains(value)) return i;
        }
        return -1;
    }

    private String cell(List<String> cells, int index) {
        return index >= 0 && index < cells.size() && cells.get(index) != null
                ? cells.get(index).trim() : "";
    }
}
