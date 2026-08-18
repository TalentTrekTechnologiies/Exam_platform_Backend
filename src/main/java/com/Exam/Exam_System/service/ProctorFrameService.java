package com.Exam.Exam_System.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * The camera view an invigilator sees of each candidate.
 *
 * Deliberately snapshots rather than live streams. Five hundred simultaneous
 * WebRTC feeds would be about 125 Mbps sustained, and no browser can decode
 * five hundred videos at once — the wall is the invigilator's machine as much
 * as the network. A still every few seconds is around 12 Mbps for the same
 * hall, renders as an ordinary grid of images, and is what invigilation
 * actually needs: who is at the desk, is anyone else in frame, has someone
 * walked away.
 *
 * Only the newest frame per candidate is kept. Each one overwrites the last,
 * so an exam of any length costs the same disk as a single round — a few tens
 * of megabytes for a full hall — and nothing has to be swept up afterwards.
 * That also means these are a live view, not a recording: there is no archive
 * of candidates' faces sitting on the server after the exam.
 */
@Service
public class ProctorFrameService {

    private static final Logger log = LoggerFactory.getLogger(ProctorFrameService.class);

    /** Generous for a small JPEG; a frame far above this is not a webcam still. */
    private static final long MAX_FRAME_BYTES = 400 * 1024;

    private final Path frameDir = Paths.get("uploads", "proctor").toAbsolutePath().normalize();

    public ProctorFrameService() {
        try {
            Files.createDirectories(frameDir);
        } catch (IOException e) {
            log.warn("Could not create the proctor frame directory: {}", e.getMessage());
        }
    }

    /** Where one candidate's newest frame lives. */
    private Path frameFor(Long attemptId) {
        return frameDir.resolve(attemptId + ".jpg");
    }

    /**
     * Stores the newest frame for an attempt, replacing the previous one.
     *
     * Never throws on a bad frame. A candidate whose camera hiccups must not
     * see an error mid-exam over something that only affects the invigilator's
     * view, so a rejected frame is simply not stored.
     */
    public boolean store(Long attemptId, MultipartFile frame) {
        if (attemptId == null || frame == null || frame.isEmpty()) return false;
        if (frame.getSize() > MAX_FRAME_BYTES) {
            log.debug("Frame for attempt {} was {} bytes; ignored.", attemptId, frame.getSize());
            return false;
        }
        try {
            Path target = frameFor(attemptId);
            // Written beside the target and moved into place, so a reader never
            // catches a half-written file and shows the invigilator a torn image.
            Path temp = Files.createTempFile(frameDir, "frame-", ".part");
            try (var in = frame.getInputStream()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | RuntimeException e) {
            log.debug("Could not store a frame for attempt {}: {}", attemptId, e.toString());
            return false;
        }
    }

    /** The newest frame, or null when that candidate has not sent one. */
    public byte[] read(Long attemptId) {
        if (attemptId == null) return null;
        Path p = frameFor(attemptId);
        try {
            return Files.exists(p) ? Files.readAllBytes(p) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** When the newest frame arrived, as epoch milliseconds; 0 when there is none. */
    public long capturedAt(Long attemptId) {
        if (attemptId == null) return 0L;
        Path p = frameFor(attemptId);
        try {
            return Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * Deletes every frame for a finished exam.
     *
     * Called when an exam's results are published: the invigilation is over, so
     * the candidates' faces have no reason to remain on disk.
     */
    public int discard(Iterable<Long> attemptIds) {
        int removed = 0;
        for (Long id : attemptIds) {
            try {
                if (Files.deleteIfExists(frameFor(id))) removed++;
            } catch (IOException e) {
                log.debug("Could not delete the frame for attempt {}: {}", id, e.toString());
            }
        }
        return removed;
    }

    /**
     * Clears frames left behind by an interrupted exam.
     *
     * Frames are normally replaced or discarded, but a server killed mid-sitting
     * leaves the last round behind. Anything older than a day cannot belong to a
     * live exam.
     */
    public int sweepOlderThan(long ageMillis) {
        long cutoff = System.currentTimeMillis() - ageMillis;
        int removed = 0;
        try (Stream<Path> files = Files.list(frameDir)) {
            for (Path p : files.sorted(Comparator.comparing(Path::toString)).toList()) {
                try {
                    if (Files.getLastModifiedTime(p).toMillis() < cutoff && Files.deleteIfExists(p)) removed++;
                } catch (IOException ignored) {
                    // One stubborn file is not worth abandoning the sweep.
                }
            }
        } catch (IOException e) {
            log.debug("Could not sweep proctor frames: {}", e.toString());
        }
        return removed;
    }
}
