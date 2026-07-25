package com.Exam.Exam_System.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RFC 4180 CSV field splitter.
 *
 * Needed because question text routinely contains commas ("If x = 2, find y"),
 * and the previous String.split(",") silently mangled or rejected every such row.
 * Handles quoted fields, embedded commas, and doubled quotes ("" -> ").
 */
public final class CsvParser {

    private CsvParser() {}

    public static List<String> parseLine(String line) {
        List<String> fields = new ArrayList<>();
        if (line == null) return fields;

        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    // A doubled quote inside a quoted field is a literal quote.
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        fields.add(current.toString().trim());
        return fields;
    }

    /** True if the row looks like a header rather than data. */
    public static boolean looksLikeHeader(List<String> fields) {
        if (fields.isEmpty()) return false;
        String first = fields.get(0).toLowerCase();
        return first.contains("question") || first.equals("q") || first.contains("hall");
    }

    public static boolean isBlank(List<String> fields) {
        return fields.stream().allMatch(f -> f == null || f.isBlank());
    }
}
