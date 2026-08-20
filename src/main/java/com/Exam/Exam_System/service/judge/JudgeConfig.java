package com.Exam.Exam_System.service.judge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.List;

/**
 * Chooses which judge runs candidate code, and refuses the dangerous choice in
 * production.
 *
 * Three settings:
 *
 *   judge.provider=judge0   the sandbox, and the only one fit for an exam
 *   judge.provider=local    the host's own compilers — development only
 *   judge.provider=none     coding questions are switched off entirely
 *
 * The default is `none`. A college that has not deployed a sandbox should not
 * discover it has been running strangers' programs on its web server; it should
 * find that coding questions cannot be set until it decides otherwise.
 */
@Configuration
public class JudgeConfig {

    private static final Logger log = LoggerFactory.getLogger(JudgeConfig.class);

    private static final List<String> PRODUCTION_PROFILES = List.of("prod", "production");

    @Value("${judge.provider:none}")
    private String provider;

    @Value("${judge.url:http://judge0:2358}")
    private String url;

    @Value("${judge.token:}")
    private String token;

    @Value("${judge.connect-timeout-seconds:5}")
    private int connectTimeoutSeconds;

    /**
     * Lets a college run the exam without a sandbox by simply not setting one
     * up — coding questions then refuse to be created, rather than being
     * created and silently unmarkable.
     */
    public static final class Disabled implements Judge {
        @Override public RunResult run(String l, String s, String in, int t, int m) {
            return RunResult.judgeError("Code execution is not configured on this installation.");
        }
        @Override public boolean available() { return false; }
        @Override public String describe() { return "No judge configured — coding questions are unavailable"; }
    }

    @Bean
    public Judge judge(Environment environment) {
        String choice = provider == null ? "none" : provider.trim().toLowerCase();

        if ("local".equals(choice)) {
            boolean production = Arrays.stream(environment.getActiveProfiles())
                    .map(String::toLowerCase)
                    .anyMatch(PRODUCTION_PROFILES::contains);

            // Loud, and fatal. The local judge runs candidate programs as the
            // application's own user with its own file access; on the machine
            // that serves the college website that is not a risk to weigh but a
            // door to leave shut. A misconfiguration here would otherwise be
            // invisible until someone went looking.
            if (production) {
                throw new IllegalStateException(
                        "judge.provider=local runs candidate code without a sandbox and cannot be used "
                        + "in production. Deploy Judge0 (see deploy/docker-compose.judge.yml) and set "
                        + "judge.provider=judge0.");
            }

            log.warn("Judge: LOCAL compilers, no sandbox. Development only — never for a real exam.");
            return new LocalJudge();
        }

        if ("judge0".equals(choice)) {
            log.info("Judge: Judge0 at {}", url);
            return new Judge0Judge(url, token, connectTimeoutSeconds);
        }

        log.info("Judge: none configured. Coding questions are unavailable on this installation.");
        return new Disabled();
    }
}
