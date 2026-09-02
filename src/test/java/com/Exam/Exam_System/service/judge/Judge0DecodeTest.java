package com.Exam.Exam_System.service.judge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decoding what Judge0 sends back.
 *
 * This is a tiny function and it broke the entire coding round. Judge0 pads its
 * base64 values with a trailing newline; Java's strict decoder rejects that as
 * an invalid character and throws; the fallback returned the raw text. So a
 * program that correctly printed 6 was compared as the literal string "Ngo="
 * against the expected "6", every case failed, and the marking looked broken
 * while the sandbox, the network and the candidate's program were all fine.
 *
 * Nothing raised an error. A candidate would simply have scored zero on a
 * correct solution, and the only clue was base64 sitting in a column labelled
 * "solution produced" — which nobody would have seen, because that column was
 * hidden from staff too.
 */
class Judge0DecodeTest {

    @Test
    @DisplayName("A trailing newline on the base64 does not stop it decoding")
    void trailingNewlineIsTolerated() {
        // Exactly what Judge0 returned for print(6): this is the bug, verbatim.
        assertEquals("6\n", Judge0Judge.unb64("Ngo=\n"));
    }

    @Test
    @DisplayName("Ordinary base64 decodes")
    void plainBase64() {
        assertEquals("6\n", Judge0Judge.unb64("Ngo="));
        assertEquals("12\n", Judge0Judge.unb64("MTIK"));
        assertEquals("-6\n", Judge0Judge.unb64("LTYK"));
    }

    @Test
    @DisplayName("Whitespace anywhere in the value is ignored")
    void whitespaceAnywhere() {
        // Long outputs come back wrapped, so the newlines are not only trailing.
        assertEquals("12\n", Judge0Judge.unb64("MT\nIK"));
        assertEquals("12\n", Judge0Judge.unb64("  MTIK  "));
    }

    @Test
    @DisplayName("Nothing at all comes back as nothing, not as the word null")
    void nullsAndBlanks() {
        assertEquals("", Judge0Judge.unb64(null));
        assertEquals("", Judge0Judge.unb64(""));
        assertEquals("", Judge0Judge.unb64("   "));
        // Jackson hands back the literal string "null" for a JSON null, which
        // would otherwise be decoded into rubbish and compared against the key.
        assertEquals("", Judge0Judge.unb64("null"));
    }

    @Test
    @DisplayName("Text that is not base64 is passed through rather than mangled")
    void plainTextSurvives() {
        // The fallback that made the original bug invisible. It is still the
        // right behaviour — a judge configured to return plain text must not
        // have its output turned into rubbish — it just must not be reached by
        // a value that IS base64 with a newline on the end.
        assertEquals("Traceback (most recent call last):",
                Judge0Judge.unb64("Traceback (most recent call last):"));
    }
}
