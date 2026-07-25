package com.Exam.Exam_System.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Turns exceptions into predictable JSON.
 *
 * Previously every failure surfaced as a raw 500 with a stack trace in the
 * response, which both leaked internals and gave the frontend nothing to branch
 * on — hence the generic "Error submitting exam" the candidate always saw.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", LocalDateTime.now());
        payload.put("status", status.value());
        payload.put("code", code);
        payload.put("message", message);
        return ResponseEntity.status(status).body(payload);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoSuchElementException e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    /**
     * A request for a route that doesn't exist is the caller's mistake, not a
     * server fault — it was surfacing as a 500 and filling the log with noise.
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> noHandler(NoHandlerFoundException e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "No such endpoint: " + e.getHttpMethod() + " " + e.getRequestURL());
    }

    /**
     * Ownership failures from AccessGuard. Without an explicit handler these fall
     * through to the catch-all below and surface as a 500, which would tell a
     * caller nothing and log a false alarm.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> forbidden(AccessDeniedException e) {
        log.warn("Access denied: {}", e.getMessage());
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage());
    }

    /**
     * A candidate whose hall ticket + name matches at more than one institution
     * on the shared platform. A known, handled condition with a genuinely useful
     * message ("use your college's own exam link") — but with no explicit handler
     * it fell through to the catch-all and reached the candidate as a generic
     * 500 "Something went wrong", hiding the one instruction that would let them
     * fix it themselves. (Never happens on a dedicated install, where the lookup
     * is already scoped to one institution.)
     */
    @ExceptionHandler(com.Exam.Exam_System.service.StudentService.AmbiguousCandidateException.class)
    public ResponseEntity<Map<String, Object>> ambiguousCandidate(RuntimeException e) {
        return body(HttpStatus.CONFLICT, "AMBIGUOUS_CANDIDATE", e.getMessage());
    }

    /** A malformed or incomplete request is the caller's error, not a server fault. */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<Map<String, Object>> malformedRequest(Exception e) {
        String message = e instanceof MissingServletRequestParameterException missing
                ? "Missing required field: " + missing.getParameterName() + "."
                : "That request could not be read. Check the fields and try again.";
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    /**
     * Exam-flow conflicts: already submitted, time over, result not ready. The
     * message doubles as a machine-readable code the client switches on.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        String message = e.getMessage() == null ? "CONFLICT" : e.getMessage();
        return body(HttpStatus.CONFLICT, message, message);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> integrity(DataIntegrityViolationException e) {
        log.warn("Data integrity violation", e);
        return body(HttpStatus.CONFLICT, "DUPLICATE", "That record already exists.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "That file is too large.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Something went wrong. Please contact the exam invigilator.");
    }
}
