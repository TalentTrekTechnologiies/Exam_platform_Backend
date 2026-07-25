package com.Exam.Exam_System.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * What actually happened during a bulk upload.
 *
 * The old flow printed skips to stdout and returned "uploaded successfully"
 * regardless — an admin could upload 180 questions, have every row rejected, and
 * be told it worked.
 */
public class UploadReport {

    public static class RowError {
        private final int line;
        private final String reason;
        private final String content;

        public RowError(int line, String reason, String content) {
            this.line = line;
            this.reason = reason;
            this.content = content;
        }

        public int getLine() { return line; }
        public String getReason() { return reason; }
        public String getContent() { return content; }
    }

    private int saved;
    private int skipped;
    private final List<RowError> errors = new ArrayList<>();

    public void recordSaved() { saved++; }

    public void recordError(int line, String reason, String content) {
        skipped++;
        // Cap the detail list so a badly-formed 10k-row file can't blow up the response.
        if (errors.size() < 100) {
            String trimmed = content == null ? "" : content.strip();
            if (trimmed.length() > 160) trimmed = trimmed.substring(0, 160) + "…";
            errors.add(new RowError(line, reason, trimmed));
        }
    }

    public int getSaved() { return saved; }
    public int getSkipped() { return skipped; }
    public List<RowError> getErrors() { return errors; }
    public boolean isSuccess() { return saved > 0 && skipped == 0; }

    public String getSummary() {
        if (saved == 0 && skipped == 0) return "The file was empty — nothing to import.";
        if (saved == 0) return "Nothing imported. All " + skipped + " row(s) were rejected.";
        if (skipped == 0) return "Imported " + saved + " row(s).";
        return "Imported " + saved + " row(s); rejected " + skipped + ".";
    }
}
