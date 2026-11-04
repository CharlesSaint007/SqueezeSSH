package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session: a {@link TerminalEmulator} coupled to a {@link TerminalBackend} that carries the
 * bytes (a local PTY subprocess, or an SSH channel).
 * <p>
 * The backend is started when the size is first made known by a call to {@link #updateSize(int, int, int, int)}.
 * All terminal emulation and callback methods are performed on the main thread.
 * <p>
 * NOTE: The terminal session may outlive the TerminalView, so be careful with callbacks!
 * <p>
 * SqueezeSSH modification: upstream Termux hard-wires a JNI PTY subprocess here. That logic now lives in
 * {@link PtyBackend}, and a session can also take colors from a per-session {@link TerminalColorScheme}.
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_BACKEND_FINISHED = 4;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /**
     * A queue written to from a backend thread when the remote end outputs, and read by the main thread to
     * process by the terminal emulator.
     */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(64 * 1024);
    /**
     * A queue written to from the main thread due to user interaction, and read by a backend thread which
     * forwards it to the remote end.
     */
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    private final TerminalBackend mBackend;
    private final Integer mTranscriptRows;
    private TerminalColorScheme mColorScheme;

    private boolean mStarted;
    private boolean mRunning = true;
    private int mExitStatus;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private static final String LOG_TAG = "TerminalSession";

    public TerminalSession(TerminalBackend backend, Integer transcriptRows, TerminalColorScheme colorScheme,
                           TerminalSessionClient client) {
        this.mBackend = backend;
        this.mTranscriptRows = transcriptRows;
        this.mColorScheme = colorScheme;
        this.mClient = client;
    }

    /** Upstream-compatible constructor for a local PTY subprocess. */
    public TerminalSession(String shellPath, String cwd, String[] args, String[] env, Integer transcriptRows,
                           TerminalSessionClient client) {
        this(new PtyBackend(shellPath, cwd, args, env), transcriptRows, null, client);
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the backend of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            mBackend.resize(columns, rows, cellWidthPixels, cellHeightPixels);
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * Set the terminal emulator's window size and start the backend.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
        if (mColorScheme != null) mEmulator.mColors.setScheme(mColorScheme);
        mStarted = true;
        mBackend.start(columns, rows, cellWidthPixels, cellHeightPixels, new TerminalBackend.Sink() {
            @Override
            public boolean onOutput(byte[] data, int offset, int count) {
                if (!mProcessToTerminalIOQueue.write(data, offset, count)) return false;
                mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                return true;
            }

            @Override
            public int readInput(byte[] buffer) {
                return mTerminalToProcessIOQueue.read(buffer, true);
            }

            @Override
            public void onFinished(int exitStatus, String message) {
                mMainThreadHandler.sendMessage(
                    mMainThreadHandler.obtainMessage(MSG_BACKEND_FINISHED, exitStatus, 0, message));
            }
        });
    }

    /** Write data to the backend. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (isRunning()) mTerminalToProcessIOQueue.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    public TerminalBackend getBackend() {
        return mBackend;
    }

    /** Switch this session's colors (e.g. the user picked another theme). Takes effect immediately. */
    public void setColorScheme(TerminalColorScheme scheme) {
        mColorScheme = scheme;
        if (mEmulator != null) {
            mEmulator.mColors.setScheme(scheme);
            notifyScreenUpdate();
        }
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Finish this terminal session (kill the subprocess / close the channel). */
    public void finishIfRunning() {
        if (isRunning()) {
            if (mStarted) {
                mBackend.terminate();
            } else {
                // Backend never started, so it will never report back; finish synchronously.
                cleanupResources(0);
                mClient.onSessionFinished(this);
            }
        }
    }

    /** Cleanup resources when the backend finishes. */
    void cleanupResources(int exitStatus) {
        synchronized (this) {
            mRunning = false;
            mExitStatus = exitStatus;
        }

        // Stop the backend I/O threads.
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        mBackend.close();
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mRunning;
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[64 * 1024];

        MainThreadHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
            }

            if (msg.what == MSG_BACKEND_FINISHED) {
                if (!isRunning()) return;
                int exitCode = msg.arg1;
                cleanupResources(exitCode);

                String exitDescription = msg.obj != null ? "\r\n[" + msg.obj : "\r\n[Process completed";
                if (msg.obj == null) {
                    if (exitCode > 0) {
                        // Non-zero process exit.
                        exitDescription += " (code " + exitCode + ")";
                    } else if (exitCode < 0) {
                        // Negated signal.
                        exitDescription += " (signal " + (-exitCode) + ")";
                    }
                }
                exitDescription += "]";

                byte[] bytesToWrite = exitDescription.getBytes(StandardCharsets.UTF_8);
                mEmulator.append(bytesToWrite, bytesToWrite.length);
                notifyScreenUpdate();

                mClient.onSessionFinished(TerminalSession.this);
            }
        }

    }

}
