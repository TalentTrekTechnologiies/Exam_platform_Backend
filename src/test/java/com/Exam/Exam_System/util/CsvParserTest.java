package com.Exam.Exam_System.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CSV parsing is where bulk question import used to lose rows silently, so the
 * cases here are the ones that actually occur in real question banks.
 */
class CsvParserTest {

    @Test
    @DisplayName("splits a plain row")
    void plainRow() {
        assertEquals(List.of("a", "b", "c"), CsvParser.parseLine("a,b,c"));
    }

    @Test
    @DisplayName("a quoted field keeps its commas — the bug that broke maths questions")
    void quotedCommas() {
        List<String> f = CsvParser.parseLine("\"If x = 2, find y\",3,4,5,6,A");
        assertEquals("If x = 2, find y", f.get(0));
        assertEquals(6, f.size());
        assertEquals("A", f.get(5));
    }

    @Test
    @DisplayName("doubled quotes collapse to a literal quote")
    void escapedQuotes() {
        List<String> f = CsvParser.parseLine("\"He said \"\"hello\"\"\",b");
        assertEquals("He said \"hello\"", f.get(0));
        assertEquals("b", f.get(1));
    }

    @Test
    @DisplayName("empty trailing field is preserved, not dropped")
    void trailingEmpty() {
        assertEquals(List.of("a", "b", ""), CsvParser.parseLine("a,b,"));
    }

    @Test
    @DisplayName("surrounding whitespace is trimmed")
    void trimsWhitespace() {
        assertEquals(List.of("a", "b"), CsvParser.parseLine("  a ,  b  "));
    }

    @Test
    @DisplayName("recognises a header row so it is not imported as a question")
    void detectsHeader() {
        assertTrue(CsvParser.looksLikeHeader(CsvParser.parseLine("questionText,optionA,optionB")));
        assertTrue(CsvParser.looksLikeHeader(CsvParser.parseLine("hallTicket,name")));
        assertFalse(CsvParser.looksLikeHeader(CsvParser.parseLine("What is 2+2?,3,4,5,6,B")));
    }

    @Test
    @DisplayName("blank rows are identified and skipped")
    void detectsBlank() {
        assertTrue(CsvParser.isBlank(CsvParser.parseLine("")));
        assertTrue(CsvParser.isBlank(CsvParser.parseLine("  , ,")));
        assertFalse(CsvParser.isBlank(CsvParser.parseLine("a,,")));
    }

    @Test
    @DisplayName("null input does not blow up the whole import")
    void nullSafe() {
        assertTrue(CsvParser.parseLine(null).isEmpty());
    }
}
