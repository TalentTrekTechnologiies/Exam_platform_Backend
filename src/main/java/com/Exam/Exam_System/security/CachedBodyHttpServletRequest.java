package com.Exam.Exam_System.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Lets the request body be read twice: once here to decide on a rate limit,
 * once more downstream by the real controller.
 *
 * A request body is a stream, readable exactly once by default — a filter
 * that peeks at it exhausts it, and the controller behind it receives nothing.
 * Spring's own ContentCachingRequestWrapper does not fix this on its own: it
 * caches only what passes through it, so it must already have been fully read
 * before the cache is usable, which is exactly the ordering problem here. This
 * instead reads the body eagerly in the constructor and serves every
 * subsequent read from that buffer, so the order callers read in stops mattering.
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.body = request.getInputStream().readAllBytes();
    }

    public byte[] getBody() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        var source = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public boolean isFinished() { return source.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) { }
            @Override public int read() { return source.read(); }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
    }
}
