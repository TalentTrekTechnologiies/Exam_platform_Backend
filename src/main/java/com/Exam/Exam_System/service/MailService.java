package com.Exam.Exam_System.service;

import com.Exam.Exam_System.Entity.OutboundEmail;
import com.Exam.Exam_System.repository.OutboundEmailRepository;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Queues mail and drains the queue at a pace the provider will tolerate.
 *
 * Nothing here sends inside a request. `queue()` writes a row and returns; a
 * scheduled drain does the sending. Two reasons, both of which bite at the
 * batch sizes this platform is built for:
 *
 *  - 2,000 synchronous sends take minutes. An admin clicking "send hall tickets"
 *    would sit on a spinner, time out, refresh, and send them all twice.
 *  - Providers rate-limit. A failure half way through a loop leaves no record of
 *    who was reached. A row per message makes that a SQL query instead.
 *
 * Sending stays disabled until SMTP is configured, and that is a normal state
 * rather than an error: messages still queue, so the whole flow can be built and
 * rehearsed before anyone has credentials.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    /**
     * Identifies this instance's claims. The exam-day stack runs three app
     * containers, so "which of us is sending this row" has to be answerable.
     */
    private final String instanceId = UUID.randomUUID().toString();

    private final OutboundEmailRepository outbox;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${app.mail.enabled:false}")
    private boolean enabled;

    @Value("${app.mail.from:}")
    private String from;

    @Value("${app.mail.from-name:Examinations}")
    private String fromName;

    /**
     * Messages per drain tick. Conservative on purpose — Microsoft 365 throttles
     * around 30/minute, and being slow is recoverable where being classified as
     * a spam source is not.
     */
    @Value("${app.mail.batch-size:25}")
    private int batchSize;

    /** How long a claim may sit before another instance may take it back. */
    @Value("${app.mail.claim-timeout-minutes:10}")
    private int claimTimeoutMinutes;

    public MailService(OutboundEmailRepository outbox, ObjectProvider<JavaMailSender> mailSenderProvider) {
        this.outbox = outbox;
        this.mailSenderProvider = mailSenderProvider;
    }

    public boolean isEnabled() {
        return enabled && from != null && !from.isBlank();
    }

    /** Writes a message to the outbox. Never sends. */
    @Transactional
    public OutboundEmail queue(Long adminId, Long examId, Long studentId,
                               String to, String subject, String html, String text) {
        OutboundEmail email = new OutboundEmail();
        email.setAdminId(adminId);
        email.setExamId(examId);
        email.setStudentId(studentId);
        email.setToAddress(to.trim());
        email.setSubject(subject);
        email.setBodyHtml(html);
        email.setBodyText(text);
        return outbox.save(email);
    }

    /**
     * Claims a batch and sends it.
     *
     * The claim is a single atomic UPDATE, so on the three-instance stack each
     * row is sent exactly once rather than three times.
     */
    @Scheduled(fixedDelayString = "${app.mail.drain-interval-ms:20000}")
    public void drain() {
        if (!isEnabled()) return;

        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            log.warn("Mail is enabled but no JavaMailSender is configured — check spring.mail.host.");
            return;
        }

        releaseStranded();

        if (claim() == 0) return;
        List<OutboundEmail> mine = outbox.findByStatusAndClaimedByOrderByIdAsc(OutboundEmail.SENDING, instanceId);

        int sent = 0, failed = 0;
        for (OutboundEmail email : mine) {
            if (sendOne(sender, email)) sent++; else failed++;
        }
        log.info("Mail drain: {} sent, {} failed, {} still queued",
                sent, failed, outbox.countByStatus(OutboundEmail.QUEUED));
    }

    // Both of these are called from drain() on the same bean, so an @Transactional
    // here would be bypassed by self-invocation and the @Modifying queries would
    // fail for want of a transaction. The boundary lives on the repository
    // methods instead, where nothing proxies around it.

    public int claim() {
        return outbox.claimBatch(instanceId, batchSize);
    }

    /** Hands back rows whose owner died mid-send, so nobody is left un-emailed. */
    public void releaseStranded() {
        int released = outbox.releaseStranded(LocalDateTime.now().minusMinutes(claimTimeoutMinutes));
        if (released > 0) log.warn("Released {} stranded email(s) back to the queue.", released);
    }

    /**
     * Sends one message and records the outcome.
     *
     * No transaction annotation, deliberately. This is self-invoked from the
     * loop in `drain()`, so one here would be silently ignored. It doesn't need
     * one: the single `outbox.save()` at the end of each path is transactional
     * in its own right, which already gives the isolation that matters — one bad
     * address cannot undo a batch of good sends.
     */
    public boolean sendOne(JavaMailSender sender, OutboundEmail email) {
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from, fromName);
            helper.setTo(email.getToAddress());
            helper.setSubject(email.getSubject());

            // Both parts: some institutional mail clients strip HTML entirely,
            // and a hall ticket arriving as a blank message is worse than none.
            if (email.getBodyText() != null && !email.getBodyText().isBlank()) {
                helper.setText(email.getBodyText(), email.getBodyHtml());
            } else {
                helper.setText(email.getBodyHtml(), true);
            }

            sender.send(message);

            email.setStatus(OutboundEmail.SENT);
            email.setSentAt(LocalDateTime.now());
            email.setLastError(null);
            email.setAttempts(email.getAttempts() + 1);
            email.setClaimedBy(null);
            outbox.save(email);
            return true;

        } catch (Exception e) {
            int attempts = email.getAttempts() + 1;
            email.setAttempts(attempts);
            email.setLastError(truncate(e.getMessage()));
            email.setClaimedBy(null);
            email.setClaimedAt(null);

            if (attempts >= OutboundEmail.MAX_ATTEMPTS) {
                // Stop retrying and surface it. A permanently bad address belongs
                // on the "not delivered" list, not in a loop forever.
                email.setStatus(OutboundEmail.FAILED);
                log.warn("Giving up on email {} to {} after {} attempts: {}",
                        email.getId(), email.getToAddress(), attempts, email.getLastError());
            } else {
                // Exponential backoff: 1, 2, 4, 8 minutes. Being throttled is the
                // common failure, and hammering the provider makes it worse.
                email.setStatus(OutboundEmail.QUEUED);
                email.setNextAttemptAt(LocalDateTime.now().plusMinutes((long) Math.pow(2, attempts - 1)));
            }
            outbox.save(email);
            return false;
        }
    }

    private static String truncate(String s) {
        if (s == null) return "unknown error";
        return s.length() > 990 ? s.substring(0, 990) + "…" : s;
    }
}
