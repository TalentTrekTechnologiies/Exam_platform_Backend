package com.Exam.Exam_System.service;

import com.Exam.Exam_System.dto.ParsedQuestion;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
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
        List<String> images = new ArrayList<>();
        try {
            if (name.endsWith(".pdf")) {
                try (PDDocument doc = Loader.loadPDF(file.getBytes())) {
                    text = readPdfInOrder(doc, images);
                }
            } else if (name.endsWith(".docx")) {
                try (XWPFDocument doc = new XWPFDocument(file.getInputStream())) {
                    text = readDocxInOrder(doc, images);
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
        // Pictures that appeared before any question started — a cover page
        // logo, a letterhead. They belong to nothing and are reported, not
        // silently pinned onto question 1.
        List<String> orphanImages = new ArrayList<>();

        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.strip();
            if (line.isEmpty()) continue;

            // An image, at the point in the document where it actually appears.
            // It belongs to whatever question is being read right now — which is
            // the whole reason the readers preserve document order.
            if (line.startsWith(IMAGE_MARKER)) {
                String file = line.substring(IMAGE_MARKER.length()).strip();
                if (current != null && !file.isEmpty()) current.getImages().add(file);
                else if (!file.isEmpty()) orphanImages.add(file);
                continue;
            }

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

        // Images are now attached where they actually appeared in the document,
        // as the parse loop walked past them. Say plainly that they still need
        // a glance: placement is a good inference, not a guarantee, and a figure
        // on the wrong question is the kind of error a reviewer catches in
        // seconds but a candidate cannot.
        long placed = questions.stream().mapToLong(q -> q.getImages().size()).sum();
        if (placed > 0) {
            documentWarnings.add(placed + " image(s) were matched to the question they appear beside. "
                    + "Check them during review — placement is inferred from the document's layout.");
        }
        if (!orphanImages.isEmpty()) {
            documentWarnings.add(orphanImages.size() + " image(s) appeared before the first question "
                    + "(a header or cover graphic) and were left unattached.");
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

    // -- Reading a document in ORDER ----------------------------------------
    //
    // Text and images used to be extracted in two separate passes, which threw
    // away the one thing that says which question a diagram belongs to: where
    // it sat in the document. Every image then landed on question 1. For a real
    // physics or chemistry paper, where most questions carry a figure, that
    // made the import close to useless.
    //
    // Both readers below emit a single stream of lines with IMAGE_MARKER lines
    // interleaved at the point the picture actually appears, so the parser can
    // attach each one to the question it is sitting next to.

    static final String IMAGE_MARKER = "\u0000IMG:";

    /**
     * Word: body elements are already in document order, and a picture lives in
     * a run inside a paragraph, so walking the body gives exact placement.
     */
    private String readDocxInOrder(XWPFDocument doc, List<String> saved) {
        StringBuilder out = new StringBuilder();
        ensureImageDir();

        for (IBodyElement element : doc.getBodyElements()) {
            if (element instanceof XWPFParagraph paragraph) {
                appendParagraph(paragraph, out, saved);
            } else if (element instanceof XWPFTable table) {
                // Papers are sometimes laid out in tables; read cells in order.
                for (var row : table.getRows()) {
                    for (var cell : row.getTableCells()) {
                        for (XWPFParagraph p : cell.getParagraphs()) appendParagraph(p, out, saved);
                    }
                }
            }
        }
        return out.toString();
    }

    private void appendParagraph(XWPFParagraph paragraph, StringBuilder out, List<String> saved) {
        String text = paragraph.getText();
        if (text != null && !text.isBlank()) out.append(text.strip()).append("\n");

        for (XWPFRun run : paragraph.getRuns()) {
            for (XWPFPicture picture : run.getEmbeddedPictures()) {
                String file = savePicture(picture.getPictureData());
                if (file != null) {
                    saved.add(file);
                    out.append(IMAGE_MARKER).append(file).append("\n");
                }
            }
        }
    }

    private String savePicture(XWPFPictureData data) {
        try {
            String extension = data.suggestFileExtension();
            if (extension == null || extension.isBlank()) extension = "png";
            String fileName = UUID.randomUUID() + "." + extension.toLowerCase(Locale.ROOT);
            Files.write(imageDir.resolve(fileName), data.getData());
            return fileName;
        } catch (IOException | RuntimeException e) {
            log.warn("Could not save an embedded image: {}", e.getMessage());
            return null;
        }
    }

    private void ensureImageDir() {
        try {
            Files.createDirectories(imageDir);
        } catch (IOException e) {
            log.warn("Could not create the image directory: {}", e.getMessage());
        }
    }

    /**
     * PDF: there is no document order to read, only marks on a page. So text
     * lines and images are both collected with their vertical position and then
     * merged top-down, per page, which reconstructs reading order well enough
     * to place a figure under the question it illustrates.
     */
    private String readPdfInOrder(PDDocument doc, List<String> saved) throws IOException {
        ensureImageDir();
        StringBuilder out = new StringBuilder();
        int pageCount = doc.getNumberOfPages();

        for (int pageNo = 1; pageNo <= pageCount; pageNo++) {
            List<Placed> items = new ArrayList<>();

            PositionalStripper stripper = new PositionalStripper(items);
            stripper.setStartPage(pageNo);
            stripper.setEndPage(pageNo);
            stripper.getText(doc);

            try {
                new ImageLocator(items, saved).processPage(doc.getPage(pageNo - 1));
            } catch (IOException | RuntimeException e) {
                // A paper whose diagrams cannot be read is still worth importing
                // for its text, so this never fails the whole operation.
                log.warn("Could not place every image on page {}: {}", pageNo, e.getMessage());
            }

            // PDF y grows upward, so the top of the page is the LARGEST y.
            items.sort((a, b) -> Double.compare(b.y(), a.y()));
            for (Placed item : items) out.append(item.line()).append("\n");
        }
        return out.toString();
    }

    /** A text line or an image marker, with where it sat on the page. */
    private record Placed(double y, String line) {}

    /** Captures each text line's vertical position instead of just its text. */
    private static final class PositionalStripper extends PDFTextStripper {
        private final List<Placed> sink;

        PositionalStripper(List<Placed> sink) throws IOException {
            this.sink = sink;
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            if (text != null && !text.isBlank() && !positions.isEmpty()) {
                sink.add(new Placed(positions.get(0).getTextMatrix().getTranslateY(), text.strip()));
            }
        }
    }

    /** Walks a page's content stream to find where each image is drawn. */
    private final class ImageLocator extends PDFStreamEngine {
        private final List<Placed> sink;
        private final List<String> saved;

        ImageLocator(List<Placed> sink, List<String> saved) {
            this.sink = sink;
            this.saved = saved;

            // Without these, the graphics state is never updated and every
            // image reports the identity matrix — position 0,0 — which is
            // exactly the silent failure this class exists to avoid. A bare
            // PDFStreamEngine registers no operator processors at all; the
            // matrix operators below are what make the CTM meaningful when a
            // "Do" is finally reached.
            addOperator(new org.apache.pdfbox.contentstream.operator.state.Save(this));
            addOperator(new org.apache.pdfbox.contentstream.operator.state.Restore(this));
            addOperator(new org.apache.pdfbox.contentstream.operator.state.Concatenate(this));
            addOperator(new org.apache.pdfbox.contentstream.operator.state.SetMatrix(this));
            addOperator(new org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters(this));
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            if (!"Do".equals(operator.getName()) || operands.isEmpty()
                    || !(operands.get(0) instanceof COSName name)) {
                super.processOperator(operator, operands);
                return;
            }

            PDResources pageResources = getResources();
            PDXObject xobject = pageResources == null ? null : pageResources.getXObject(name);
            if (!(xobject instanceof PDImageXObject image)) {
                super.processOperator(operator, operands);
                return;
            }

            try {
                String fileName = UUID.randomUUID() + ".png";
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ImageIO.write(image.getImage(), "png", bytes);
                Files.write(imageDir.resolve(fileName), bytes.toByteArray());
                saved.add(fileName);

                // Sort by the image's vertical CENTRE.
                //
                // An image's CTM maps the unit square onto the placed rectangle,
                // so translateY is the BOTTOM edge and the vertical scale is the
                // height. Neither edge alone is reliable: the bottom files a
                // figure under the question that follows it, while the top
                // misfiles any figure whose upper edge overlaps the line above
                // — measured at 5pt of overlap in a perfectly ordinary layout.
                // The centre sits unambiguously between the question it belongs
                // to and the next one, whatever the figure's height.
                var ctm = getGraphicsState().getCurrentTransformationMatrix();
                double centre = ctm.getTranslateY() + Math.abs(ctm.getScaleY()) / 2.0;
                sink.add(new Placed(centre, IMAGE_MARKER + fileName));
            } catch (IOException | RuntimeException e) {
                log.warn("Could not save an image from the PDF: {}", e.getMessage());
            }
        }
    }

}
