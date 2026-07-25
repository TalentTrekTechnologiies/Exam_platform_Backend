package com.Exam.Exam_System.service;

import com.Exam.Exam_System.dto.ParsedQuestion;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading a question paper out of a PDF or Word document.
 *
 * This is genuinely lossy work. Question papers are typeset for people, not
 * parsers: numbering styles vary, options wrap across lines, answer keys live
 * anywhere or nowhere. So this service is built to be *honest* rather than
 * clever — it extracts what it can, flags what it is unsure about, and hands
 * back a proposal for a human to correct. Nothing here writes to the question
 * bank; that only happens after review.
 */
@Service
public class DocumentImportService {

    private static final Logger log = LoggerFactory.getLogger(DocumentImportService.class);

    /** "1." "1)" "Q1." "Q.1" — the numbering styles papers actually use. */
    private static final Pattern QUESTION_START =
            Pattern.compile("^\\s*(?:Q(?:uestion)?\\s*\\.?\\s*)?(\\d{1,3})\\s*[.)\\]:]\\s*(.*)$",
                    Pattern.CASE_INSENSITIVE);

    /** "(a) text", "A. text", "a) text". */
    private static final Pattern OPTION =
            Pattern.compile("^\\s*[(\\[]?([A-Da-d])[)\\].:]\\s*(.+)$");

    /** "Answer: B", "Ans - B", "Correct Answer : (b)". */
    private static final Pattern ANSWER =
            Pattern.compile("^\\s*(?:correct\\s*)?ans(?:wer)?\\s*[:\\-–]?\\s*[(\\[]?([A-Da-d])[)\\]]?\\s*$",
                    Pattern.CASE_INSENSITIVE);

    /** A heading such as "SECTION A" or "PART II — Chemistry". */
    private static final Pattern SECTION =
            Pattern.compile("^\\s*(?:section|part)\\s+([A-Za-z0-9]+)\\s*[-–:]?\\s*(.*)$",
                    Pattern.CASE_INSENSITIVE);

    /** "[4 marks]", "(4 Marks)", "Marks: 4". */
    private static final Pattern MARKS =
            Pattern.compile("[\\[(]?\\s*(?:marks?\\s*[:\\-]?\\s*)?(\\d{1,2})\\s*marks?\\s*[\\])]?",
                    Pattern.CASE_INSENSITIVE);

    private final Path imageDir = Paths.get("uploads").toAbsolutePath().normalize();

    /** Everything a review screen needs: the proposals plus how the parse went. */
    public record ImportPreview(
            List<ParsedQuestion> questions,
            int usableCount,
            int needsAttentionCount,
            List<String> documentWarnings,
            String sourceFileName) {}

    public ImportPreview parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No document was uploaded.");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);

        String text;
        List<String> images;
        try {
            if (name.endsWith(".pdf")) {
                try (PDDocument doc = Loader.loadPDF(file.getBytes())) {
                    text = new PDFTextStripper().getText(doc);
                    images = extractPdfImages(doc);
                }
            } else if (name.endsWith(".docx")) {
                try (XWPFDocument doc = new XWPFDocument(file.getInputStream())) {
                    text = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(doc).getText();
                    images = extractDocxImages(doc);
                }
            } else if (name.endsWith(".doc")) {
                throw new IllegalArgumentException(
                        "The old .doc format cannot be read reliably. Save it as .docx or PDF and try again.");
            } else {
                throw new IllegalArgumentException(
                        "Upload a PDF or a Word (.docx) file. For a spreadsheet, use the CSV import instead.");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("That file could not be opened: " + e.getMessage());
        }

        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(
                    "No text could be read from that document. If it is a scan, the pages are images and "
                            + "would need optical character recognition, which this import does not do.");
        }

        return build(text, images, file.getOriginalFilename());
    }

    private ImportPreview build(String text, List<String> images, String fileName) {
        List<ParsedQuestion> questions = new ArrayList<>();
        List<String> documentWarnings = new ArrayList<>();

        ParsedQuestion current = null;
        String currentSection = null;
        StringBuilder pendingText = new StringBuilder();
        String lastOptionLetter = null;

        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.strip();
            if (line.isEmpty()) continue;

            Matcher section = SECTION.matcher(line);
            if (section.matches() && line.length() < 60) {
                String label = section.group(2).isBlank()
                        ? "Section " + section.group(1)
                        : section.group(2).strip();
                currentSection = label;
                continue;
            }

            Matcher answer = ANSWER.matcher(line);
            if (answer.matches() && current != null) {
                current.setCorrectAnswer(answer.group(1).toUpperCase(Locale.ROOT));
                continue;
            }

            Matcher option = OPTION.matcher(line);
            if (option.matches() && current != null) {
                flushInto(current, pendingText, lastOptionLetter);
                lastOptionLetter = option.group(1).toUpperCase(Locale.ROOT);
                pendingText = new StringBuilder(option.group(2).strip());
                continue;
            }

            Matcher start = QUESTION_START.matcher(line);
            if (start.matches()) {
                if (current != null) {
                    flushInto(current, pendingText, lastOptionLetter);
                    finish(current, questions);
                }
                current = new ParsedQuestion();
                current.setSourceNumber(Integer.parseInt(start.group(1)));
                current.setSectionName(currentSection);
                pendingText = new StringBuilder(start.group(2).strip());
                lastOptionLetter = null;

                Matcher marks = MARKS.matcher(line);
                if (marks.find()) {
                    try { current.setMarks(Integer.parseInt(marks.group(1))); } catch (NumberFormatException ignored) { }
                }
                continue;
            }

            // A continuation of whatever we are currently reading — a question
            // stem or an option that wrapped onto the next line.
            if (current != null) pendingText.append(" ").append(line);
        }

        if (current != null) {
            flushInto(current, pendingText, lastOptionLetter);
            finish(current, questions);
        }

        if (questions.isEmpty()) {
            documentWarnings.add("No questions could be recognised. This import expects numbered questions "
                    + "(1. 2. 3.) with lettered options (A) B) C) D)). Check the document's layout, "
                    + "or use the CSV import for full control.");
        }

        // Images cannot be attached to a specific question reliably — a picture's
        // position in the file says little about which question it belongs to.
        // They are offered for manual attachment rather than guessed at.
        if (!images.isEmpty()) {
            documentWarnings.add(images.size() + " image(s) were extracted from the document. "
                    + "Attach them to the right questions during review — their position in the file "
                    + "is not a reliable guide to which question they belong to.");
            if (!questions.isEmpty()) questions.get(0).setImages(images);
        }

        int usable = (int) questions.stream().filter(ParsedQuestion::isUsable).count();
        int attention = (int) questions.stream().filter(q -> !q.getIssues().isEmpty()).count();

        log.info("Parsed {} question(s) from {} — {} usable, {} needing attention.",
                questions.size(), fileName, usable, attention);

        return new ImportPreview(questions, usable, attention, documentWarnings, fileName);
    }

    /** Files the accumulated text into the stem or the option it belongs to. */
    private void flushInto(ParsedQuestion q, StringBuilder buffer, String optionLetter) {
        String value = buffer.toString().strip();
        if (value.isEmpty()) return;

        if (optionLetter == null) {
            q.setQuestionText(value);
            return;
        }
        switch (optionLetter) {
            case "A" -> q.setOptionA(value);
            case "B" -> q.setOptionB(value);
            case "C" -> q.setOptionC(value);
            case "D" -> q.setOptionD(value);
            default -> { }
        }
    }

    /** Records everything questionable about a parsed row, then keeps it. */
    private void finish(ParsedQuestion q, List<ParsedQuestion> into) {
        // A marks annotation is as likely to sit at the end of a wrapped stem —
        // "Find its momentum. [4 marks]" — as on the numbered line, so the whole
        // stem is searched. It is then stripped out: a candidate should read the
        // question, not the paper's own typesetting notes.
        if (q.getQuestionText() != null) {
            Matcher marks = MARKS.matcher(q.getQuestionText());
            if (marks.find()) {
                if (q.getMarks() == null) {
                    try { q.setMarks(Integer.parseInt(marks.group(1))); } catch (NumberFormatException ignored) { }
                }
                q.setQuestionText(q.getQuestionText()
                        .replaceAll(MARKS.pattern(), "")
                        .replaceAll("\\s{2,}", " ")
                        .strip());
            }
        }

        if (q.getQuestionText() == null || q.getQuestionText().isBlank()) {
            q.addIssue("No question text was found.");
        }
        if (q.getOptionA() == null || q.getOptionB() == null) {
            q.addIssue("Fewer than two options were found — check the option letters in the document.");
        }
        if (q.getOptionC() == null || q.getOptionD() == null) {
            q.addIssue("Only some options were found. Confirm the question really has this many.");
        }
        if (q.getCorrectAnswer() == null) {
            // The single most important field, and the one most often absent —
            // many papers keep the key in a separate document entirely.
            q.addIssue("No answer key was found. Set the correct option before importing.");
        }
        if (q.getQuestionText() != null && q.getQuestionText().length() > 1000) {
            q.addIssue("The question text is unusually long — two questions may have run together.");
        }
        into.add(q);
    }

    private List<String> extractPdfImages(PDDocument doc) {
        List<String> saved = new ArrayList<>();
        try {
            Files.createDirectories(imageDir);
            for (PDPage page : doc.getPages()) {
                PDResources resources = page.getResources();
                if (resources == null) continue;
                for (var xobjectName : resources.getXObjectNames()) {
                    PDXObject xobject = resources.getXObject(xobjectName);
                    if (!(xobject instanceof PDImageXObject image)) continue;

                    String fileName = UUID.randomUUID() + ".png";
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    ImageIO.write(image.getImage(), "png", out);
                    Files.write(imageDir.resolve(fileName), out.toByteArray());
                    saved.add(fileName);
                }
            }
        } catch (IOException | RuntimeException e) {
            // A paper whose diagrams cannot be read is still worth importing for
            // its text, so this never fails the whole operation.
            log.warn("Could not extract every image from the PDF: {}", e.getMessage());
        }
        return saved;
    }

    private List<String> extractDocxImages(XWPFDocument doc) {
        List<String> saved = new ArrayList<>();
        try {
            Files.createDirectories(imageDir);
            for (XWPFPictureData picture : doc.getAllPictures()) {
                String extension = picture.suggestFileExtension();
                if (extension == null || extension.isBlank()) extension = "png";
                String fileName = UUID.randomUUID() + "." + extension.toLowerCase(Locale.ROOT);
                Files.write(imageDir.resolve(fileName), picture.getData());
                saved.add(fileName);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not extract every image from the document: {}", e.getMessage());
        }
        return saved;
    }
}
