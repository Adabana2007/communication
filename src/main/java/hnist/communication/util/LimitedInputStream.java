package hnist.communication.util;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** 只允许读取指定字节数的输入流，确保 Range 响应体与 Content-Length 严格一致。 */
public final class LimitedInputStream extends FilterInputStream {

    private long remaining;

    public LimitedInputStream(InputStream inputStream, long limit) {
        super(Objects.requireNonNull(inputStream, "inputStream"));
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }
        this.remaining = limit;
    }

    @Override
    public int read() throws IOException {
        if (remaining <= 0) {
            return -1;
        }
        int value = super.read();
        if (value != -1) {
            remaining--;
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (remaining <= 0) {
            return -1;
        }
        int read = super.read(buffer, offset, (int) Math.min(length, remaining));
        if (read > 0) {
            remaining -= read;
        }
        return read;
    }

    @Override
    public long skip(long count) throws IOException {
        if (count <= 0 || remaining <= 0) {
            return 0;
        }
        long skipped = super.skip(Math.min(count, remaining));
        remaining -= skipped;
        return skipped;
    }

    @Override
    public int available() throws IOException {
        return (int) Math.min(super.available(), remaining);
    }
}
