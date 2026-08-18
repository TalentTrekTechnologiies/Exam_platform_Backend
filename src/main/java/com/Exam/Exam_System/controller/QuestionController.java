package com.Exam.Exam_System.controller;

import com.Exam.Exam_System.Entity.Question;
import com.Exam.Exam_System.dto.UploadReport;
import com.Exam.Exam_System.security.AccessGuard;
import com.Exam.Exam_System.Entity.Exam;
import com.Exam.Exam_System.dto.ParsedQuestion;
import com.Exam.Exam_System.service.DocumentImportService;
import com.Exam.Exam_System.service.QuestionService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Question bank management.
 *
 * Every response here contains the correct answer, so the whole controller sits
 * behind ROLE_ADMIN and every path is ownership-checked. The old
 * GET /admin/question/all returned every question in the database — across all
 * institutions — with no authentication at all.
 */
@RestController
@RequestMapping("/admin/question")
public class QuestionController {

    private final QuestionService questionService;
    private final AccessGuard accessGuard;
    private final DocumentImportService documentImportService;

    public QuestionController(QuestionService questionService,
                              AccessGuard accessGuard,
                              DocumentImportService documentImportService) {
        this.questionService = questionService;
        this.accessGuard = accessGuard;
        this.documentImportService = documentImportService;
    }

    @PostMapping
    public Question addQuestion(@RequestBody Question question) {
        accessGuard.requireOwnedExam(question.getExamId());
        if (question.getSectionId() != null) accessGuard.requireOwnedSection(question.getSectionId());
        return questionService.addQuestion(question);
    }

    @GetMapping("/{examId}")
    public List<Question> getQuestions(@PathVariable Long examId) {
        accessGuard.requireOwnedExam(examId);
        return questionService.getQuestionsByExam(examId);
    }

    @PutMapping("/{id}")
    public Question updateQuestion(@PathVariable Long id, @RequestBody Question question) {
        Question existing = accessGuard.requireOwnedQuestion(id);
        // A question cannot be moved into another institution's exam.
        question.setExamId(existing.getExamId());
        if (question.getSectionId() != null) accessGuard.requireOwnedSection(question.getSectionId());
        return questionService.updateQuestion(id, question);
    }

    @DeleteMapping("/{id}")
    public void deleteQuestion(@PathVariable Long id) {
        accessGuard.requireOwnedQuestion(id);
        questionService.deleteQuestion(id);
    }

    @PostMapping("/upload")
    public UploadReport uploadQuestions(@RequestParam("file") MultipartFile file,
                                        @RequestParam Long examId) {
        accessGuard.requireOwnedExam(examId);
        return questionService.uploadQuestions(file, examId);
    }

    /**
     * Reads a PDF or Word question paper and returns what it found — WITHOUT
     * saving anything.
     *
     * Document parsing cannot be trusted blind: a mis-read answer key is the one
     * error the system can never detect for itself, and it would mark every
     * candidate against it. So this deliberately stops at a proposal, and the
     * separate confirm step is what actually writes.
     */
    @PostMapping("/import/preview")
    public DocumentImportService.ImportPreview previewDocument(@RequestParam("file") MultipartFile file,
                                                               @RequestParam Long examId) {
        Exam exam = accessGuard.requireOwnedExam(examId);
        DocumentImportService.ImportPreview preview = documentImportService.parse(file);

        // Show the marks these questions will actually carry.
        //
        // Most papers do not print "[4 marks]" against every question, so the
        // parser rightly leaves marks blank — and the exam's own scheme was
        // filled in later, at save time. The review screen therefore showed an
        // empty marks column for a paper that would be marked perfectly well,
        // which reads as the scheme having been ignored. Applying it here makes
        // the screen show what will be saved, and it stays editable.
        for (ParsedQuestion q : preview.questions()) {
            if (q.getMarks() == null && exam.getDefaultMarks() != null) {
                q.setMarks(exam.getDefaultMarks());
            }
            if (q.getNegativeMarks() == null && exam.getDefaultNegativeMarks() != null) {
                q.setNegativeMarks(exam.getDefaultNegativeMarks());
            }
        }
        return preview;
    }

    /**
     * Saves questions the admin has reviewed and corrected.
     *
     * Takes the edited list back, not a file — so what is stored is what a human
     * approved on screen, never what the parser guessed.
     */
    @PostMapping("/import/confirm")
    public UploadReport confirmImport(@RequestBody Map<String, Object> request) {
        Long examId = Long.valueOf(String.valueOf(request.get("examId")));
        accessGuard.requireOwnedExam(examId);
        return questionService.importReviewed(examId, request.get("questions"));
    }

    @GetMapping("/raw/{id}")
    public Question getQuestionById(@PathVariable Long id) {
        return accessGuard.requireOwnedQuestion(id);
    }
}
