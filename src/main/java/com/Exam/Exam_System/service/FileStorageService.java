package com.Exam.Exam_System.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Stores uploaded images and videos.
 *
 * The old controllers wrote `getOriginalFilename()` straight into the uploads
 * directory with no validation at all — a filename of "../../application.properties"
 * would escape the directory, and any executable content was accepted and then
 * served back publicly.
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "svg");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "webm", "ogg", "mov");

    private static final Set<String> IMAGE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/svg+xml");
    private static final Set<String> VIDEO_TYPES = Set.of(
            "video/mp4", "video/webm", "video/ogg", "video/quicktime");

    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final long MAX_VIDEO_BYTES = 100L * 1024 * 1024;

    private final Path root = Paths.get("uploads").toAbsolutePath().normalize();

    public String storeImage(MultipartFile file) {
        return store(file, IMAGE_EXTENSIONS, IMAGE_TYPES, MAX_IMAGE_BYTES, "image");
    }

    public String storeVideo(MultipartFile file) {
        return store(file, VIDEO_EXTENSIONS, VIDEO_TYPES, MAX_VIDEO_BYTES, "video");
    }

    private String store(MultipartFile file, Set<String> allowedExtensions,
                         Set<String> allowedTypes, long maxBytes, String kind) {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No " + kind + " was uploaded.");
        }
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException(
                    "That " + kind + " is larger than " + (maxBytes / 1024 / 1024) + "MB.");
        }

        String extension = extensionOf(file.getOriginalFilename());
        if (!allowedExtensions.contains(extension)) {
            throw new IllegalArgumentException(
                    "Unsupported " + kind + " format. Allowed: " + String.join(", ", allowedExtensions));
        }

        String contentType = file.getContentType() == null
                ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!allowedTypes.contains(contentType)) {
            throw new IllegalArgumentException("That file is not a valid " + kind + ".");
        }

        // The stored name is generated, never derived from user input, so a
        // crafted filename has nothing to traverse with.
        String storedName = UUID.randomUUID() + "." + extension;

        try {
            Files.createDirectories(root);
            Path target = root.resolve(storedName).normalize();

            // Belt and braces: refuse anything that resolved outside the root.
            if (!target.startsWith(root)) {
                throw new IllegalArgumentException("Invalid upload path.");
            }

            try (var in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }

            log.info("Stored {} as {} ({} bytes)", kind, storedName, file.getSize());
            return storedName;

        } catch (IOException e) {
            log.error("Failed to store {}", kind, e);
            throw new IllegalStateException("UPLOAD_FAILED");
        }
    }

    private String extensionOf(String filename) {
        if (filename == null) return "";
        // Strip any path the client may have sent before looking at the extension.
        String base = Paths.get(filename).getFileName().toString();
        int dot = base.lastIndexOf('.');
        return dot < 0 ? "" : base.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
