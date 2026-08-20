package com.Exam.Exam_System.service.judge;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Runs candidate code inside Judge0.
 *
 * Judge0 is a sandboxed execution service: it compiles and runs a submission in
 * an isolate jail with no network, a wall-clock limit, a memory ceiling and a
 * process cap, then hands back stdout, stderr and a status. That isolation is
 * the reason to use it rather than shelling out to a compiler — a coding exam
 * means running programs written by five thousand strangers, some of whom will
 * try, and the server they run on also serves the college's website.
 *
 * Deployed as its own container. See deploy/docker-compose.judge.yml.
 */
public class Judge0Judge implements Judge {

    private static final Logger log = LoggerFactory.getLogger(Judge0Judge.class);

    private final String baseUrl;
    private final String authToken;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public Judge0Judge(String baseUrl, String authToken, int connectTimeoutSeconds) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.authToken = authToken;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();
    }

    @Override
    public String describe() {
        return "Judge0 at " + baseUrl;
    }

    @Override
    public boolean available() {
        try {
            HttpResponse<String> r = http.send(
                    request("/languages").GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (Exception e) {
            log.warn("Judge0 at {} is not answering: {}", baseUrl, e.getMessage());
            return false;
        }
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30));
        if (authToken != null && !authToken.isBlank()) b.header("X-Auth-Token", authToken);
        return b;
    }

    @Override
    public RunResult run(String languageId, String source, String stdin,
                         int timeLimitMs, int memoryLimitMb) {

        Language language = Language.byId(languageId).orElse(null);
        if (language == null) return RunResult.judgeError("Unknown language: " + languageId);

        try {
            ObjectNode body = json.createObjectNode();
            body.put("language_id", language.judge0Id());
            // Base64 throughout: a program is full of quotes, backslashes and
            // newlines, and so is its input. Encoding both ends removes an
            // entire class of "it worked until someone printed a quote".
            body.put("source_code", b64(language.normaliseSource(source)));
            body.put("stdin", b64(stdin == null ? "" : stdin));
            body.put("cpu_time_limit", Math.max(1, timeLimitMs) / 1000.0);
            // A hard ceiling above the CPU limit, so a program that sleeps
            // rather than spins is still reaped.
            body.put("wall_time_limit", Math.max(1, timeLimitMs) / 1000.0 + 2.0);
            body.put("memory_limit", Math.max(16, memoryLimitMb) * 1024);   // Judge0 wants KB
            body.put("max_processes_and_or_threads", 60);
            body.put("enable_network", false);

            HttpResponse<String> response = http.send(
                    request("/submissions?base64_encoded=true&wait=true")
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                return RunResult.judgeError("Judge0 answered " + response.statusCode());
            }
            return parse(json.readTree(response.body()));

        } catch (Exception e) {
            log.error("Judge0 run failed", e);
            return RunResult.judgeError(e.getMessage());
        }
    }

    /**
     * Judge0's status ids, mapped to something the rest of the platform can
     * reason about. 3 is Accepted — meaning "it ran", not "it was right":
     * comparing the output against the expected one is our job, not the
     * sandbox's.
     */
    private RunResult parse(JsonNode n) {
        int statusId = n.path("status").path("id").asInt(0);
        String stdout = unb64(n.path("stdout").asText(null));
        String stderr = unb64(n.path("stderr").asText(null));
        String compile = unb64(n.path("compile_output").asText(null));
        double seconds = n.path("time").asDouble(0);
        Integer exit = n.hasNonNull("exit_code") ? n.get("exit_code").asInt() : null;

        RunResult.Status status = switch (statusId) {
            case 3 -> RunResult.Status.OK;
            case 5 -> RunResult.Status.TIMEOUT;
            case 6 -> RunResult.Status.COMPILE_ERROR;
            case 7, 8, 9, 10, 11, 12 -> RunResult.Status.RUNTIME_ERROR;
            default -> statusId >= 13 ? RunResult.Status.JUDGE_ERROR : RunResult.Status.RUNTIME_ERROR;
        };

        // Judge0 reports an out-of-memory kill as a signal, so the distinction
        // is only recoverable from the message.
        String description = n.path("status").path("description").asText("");
        if (description.toLowerCase().contains("memory")) status = RunResult.Status.MEMORY_EXCEEDED;

        return new RunResult(status, stdout == null ? "" : stdout,
                stderr == null ? "" : stderr, compile == null ? "" : compile,
                Math.round(seconds * 1000), exit);
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String s) {
        if (s == null || s.isBlank() || "null".equals(s)) return "";
        try {
            return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;   // already plain
        }
    }
}
