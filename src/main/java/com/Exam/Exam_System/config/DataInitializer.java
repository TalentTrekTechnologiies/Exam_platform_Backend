package com.Exam.Exam_System.config;

import com.Exam.Exam_System.Entity.Admin;
import com.Exam.Exam_System.repository.AdminRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Locale;

/**
 * Seeds a first admin so a fresh install has something to log in with.
 *
 * This is the only way in on a dedicated install — KSRM running its own exam
 * module — because self-registration is closed there. It therefore has to
 * produce a *complete* institution, not a placeholder: the seeded row needs the
 * real college name and, above all, the institution code.
 *
 * The code is load-bearing. A dedicated frontend is built with
 * VITE_INSTITUTION_CODE pinned, and candidate sign-in resolves that code to an
 * institution before it will look up a hall ticket. Seeding an admin without one
 * left every candidate facing "That exam link is not valid" — on exam day, and
 * for all of them at once.
 */
@Configuration
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Value("${app.seed.admin-email:admin@example.edu}")
    private String seedEmail;

    @Value("${app.seed.admin-password:}")
    private String seedPassword;

    @Value("${app.seed.admin-college:Default Institution}")
    private String seedCollege;

    /** URL slug for the institution. Must match the frontend's VITE_INSTITUTION_CODE. */
    @Value("${app.seed.admin-code:}")
    private String seedCode;

    @Value("${app.registration.enabled:true}")
    private boolean registrationEnabled;

    @Bean
    public CommandLineRunner init(AdminRepository adminRepository, PasswordEncoder passwordEncoder) {
        return args -> {
            if (adminRepository.count() > 0) return;

            if (seedPassword == null || seedPassword.isBlank()) {
                // With registration closed and no account seeded there is no way
                // in at all — not through the UI and not through the API. Said
                // loudly here because the alternative is discovering it when
                // someone tries to log in for the first time.
                if (!registrationEnabled) {
                    log.error("""

                            ─────────────────────────────────────────────────────────────
                             NO WAY IN. This installation has no admin accounts, and
                             registration is disabled (ALLOW_REGISTRATION=false).
                             Set SEED_ADMIN_PASSWORD (and SEED_ADMIN_CODE) and restart.
                            ─────────────────────────────────────────────────────────────""");
                } else {
                    log.warn("""

                            ─────────────────────────────────────────────────────────────
                             No admin accounts exist and SEED_ADMIN_PASSWORD is not set.
                             Register the first institution at POST /admin/register,
                             or set SEED_ADMIN_PASSWORD and restart to seed one.
                            ─────────────────────────────────────────────────────────────""");
                }
                return;
            }

            Admin admin = new Admin();
            admin.setCollegeName(seedCollege == null || seedCollege.isBlank()
                    ? "Default Institution" : seedCollege.trim());
            admin.setEmail(seedEmail.toLowerCase(Locale.ROOT));
            admin.setPassword(passwordEncoder.encode(seedPassword));

            String code = seedCode == null ? "" : seedCode.trim().toLowerCase(Locale.ROOT);
            if (!code.isEmpty()) admin.setCode(code);

            adminRepository.save(admin);

            log.info("Seeded initial admin: {} for \"{}\" (institution code: {})",
                    admin.getEmail(), admin.getCollegeName(),
                    code.isEmpty() ? "none — set SEED_ADMIN_CODE if the frontend pins one" : code);

            if (code.isEmpty() && !registrationEnabled) {
                log.warn("""

                        ─────────────────────────────────────────────────────────────
                         SEED_ADMIN_CODE is not set on a dedicated installation.
                         If the frontend was built with VITE_INSTITUTION_CODE, every
                         candidate sign-in will be refused as an invalid exam link.
                        ─────────────────────────────────────────────────────────────""");
            }
        };
    }
}
