package com.termux.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A terminal backend over a pair of byte streams, e.g. an SSH shell channel. Subclasses provide the streams
 * and react to resizes and termination; this class runs the reader and writer threads.
 */
public abstract class StreamBackend implements TerminalBackend {

    private volatile boolean mFinished;
    private Sink mSink;

    /** Open the remote side and return its stdout (merged with stderr if the transport does that). */
    protected abstract InputStream openInput(int columns, int rows) throws IOException;

    /** The stream that user input is written to; called once, after {@link #openInput}. */
    protected abstract OutputStream openOutput() throws IOException;

    /** Called when the stream ends by itself; return the message to show, or null for the default. */
    protected String describeEndOfStream() {
        return "Connection closed";
    }

    @Override
    public final void start(int columns, int rows, int cellWidthPixels, int cellHeightPixels, Sink sink) {
        mSink = sink;
        new Thread("StreamBackend-io") {
            @Override
            public void run() {
                InputStream in;
                OutputStream out;
                try {
                    in = openInput(columns, rows);
                    out = openOutput();
                } catch (IOException e) {
                    finish(1, "Failed to open session: " + e.getMessage());
                    return;
                }
                startWriter(out);
                try {
                    final byte[] buffer = new byte[8192];
                    while (true) {
                        int read = in.read(buffer);
                        if (read == -1) break;
                        if (!sink.onOutput(buffer, 0, read)) return;
                    }
                    finish(0, describeEndOfStream());
                } catch (IOException e) {
                    finish(1, "Connection lost: " + e.getMessage());
                }
            }
        }.start();
    }

    private void startWriter(OutputStream out) {
        new Thread("StreamBackend-writer") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try {
                    while (true) {
                        int count = mSink.readInput(buffer);
                        if (count == -1) return;
                        out.write(buffer, 0, count);
                        out.flush();
                    }
                } catch (IOException e) {
                    finish(1, "Connection lost: " + e.getMessage());
                }
            }
        }.start();
    }

    /** Report the end of the session exactly once. Subclasses call this from {@link #terminate()} as well. */
    protected final void finish(int exitStatus, String message) {
        synchronized (this) {
            if (mFinished) return;
            mFinished = true;
        }
        if (mSink != null) mSink.onFinished(exitStatus, message);
    }
}
