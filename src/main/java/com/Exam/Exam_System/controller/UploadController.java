package com.Exam.Exam_System.controller;

import com.Exam.Exam_System.service.FileStorageService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Admin-only asset uploads. Validation and safe naming live in FileStorageService.
 */
@RestController
@RequestMapping("/upload")
public class UploadController {

    private final FileStorageService fileStorage;

    public UploadController(FileStorageService fileStorage) {
        this.fileStorage = fileStorage;
    }

    @PostMapping("/logo")
    public Map<String, String> uploadImage(@RequestParam("file") MultipartFile file) {
        return Map.of("filename", fileStorage.storeImage(file));
    }

    @PostMapping("/video")
    public Map<String, String> uploadVideo(@RequestParam("file") MultipartFile file) {
        return Map.of("filename", fileStorage.storeVideo(file));
    }
}
