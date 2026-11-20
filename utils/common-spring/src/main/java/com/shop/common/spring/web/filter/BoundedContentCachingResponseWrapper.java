package com.shop.common.spring.web.filter;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class BoundedContentCachingResponseWrapper extends HttpServletResponseWrapper {

    private final ByteArrayOutputStream cache = new ByteArrayOutputStream();
    private final int cacheLimit;

    private ServletOutputStream outputStream;
    private PrintWriter writer;
    private boolean truncated;

    public BoundedContentCachingResponseWrapper(HttpServletResponse response, int cacheLimit) {
        super(response);
        this.cacheLimit = Math.max(0, cacheLimit);
    }

    public byte[] getContentAsByteArray() {
        return cache.toByteArray();
    }

    public boolean isTruncated() {
        return truncated;
    }

    public int getCacheLimit() {
        return cacheLimit;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (writer != null) {
            throw new IllegalStateException("getWriter() has already been called on this response");
        }
        return stream();
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (outputStream != null && writer == null) {
            throw new IllegalStateException("getOutputStream() has already been called on this response");
        }
        if (writer == null) {
            writer = new PrintWriter(new OutputStreamWriter(stream(), charset()), false);
        }
        return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        super.flushBuffer();
    }

    private ServletOutputStream stream() throws IOException {
        if (outputStream == null) {
            outputStream = new BoundedServletOutputStream(super.getOutputStream());
        }
        return outputStream;
    }

    private Charset charset() {
        String encoding = getCharacterEncoding();
        if (encoding == null || encoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (RuntimeException unsupportedEncoding) {
            return StandardCharsets.UTF_8;
        }
    }

    private void capture(byte[] bytes, int offset, int length) {
        if (cacheLimit == 0) {
            truncated = truncated || length > 0;
            return;
        }
        int remaining = cacheLimit - cache.size();
        if (remaining <= 0) {
            truncated = true;
            return;
        }
        int allowed = Math.min(length, remaining);
        cache.write(bytes, offset, allowed);
        if (allowed < length) {
            truncated = true;
        }
    }

    private final class BoundedServletOutputStream extends ServletOutputStream {

        private final ServletOutputStream delegate;

        private BoundedServletOutputStream(ServletOutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int value) throws IOException {
            capture(new byte[]{(byte) value}, 0, 1);
            delegate.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            capture(bytes, offset, length);
            delegate.write(bytes, offset, length);
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            delegate.setWriteListener(writeListener);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.flush();
        }
    }
}
