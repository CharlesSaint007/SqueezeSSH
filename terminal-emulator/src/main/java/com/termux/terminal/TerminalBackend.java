package com.termux.terminal;

/**
 * Carries bytes between a {@link TerminalSession} and whatever is on the other end: a local PTY subprocess
 * ({@link PtyBackend}) or a remote shell over a pair of streams ({@link StreamBackend}).
 * <p>
 * SqueezeSSH addition; upstream Termux has the PTY hard-wired into {@link TerminalSession}.
 */
public interface TerminalBackend {

    /** How a backend talks back to its session. Methods may be called from any thread. */
    interface Sink {
        /** Deliver output for the emulator. Returns false once the session is closed, so the reader can stop. */
        boolean onOutput(byte[] data, int offset, int count);

        /** Block until user input is available, then copy it into [buffer]. Returns -1 once the session is closed. */
        int readInput(byte[] buffer);

        /**
         * The backend has ended. [exitStatus] follows the PTY convention: positive exit code, negative signal.
         * [message] replaces the default "Process completed" line when non-null (e.g. "Connection closed").
         */
        void onFinished(int exitStatus, String message);
    }

    /** Begin I/O with the initial terminal size. Called once, on the main thread. */
    void start(int columns, int rows, int cellWidthPixels, int cellHeightPixels, Sink sink);

    /** The terminal was resized. */
    void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels);

    /** Ask the far end to stop. The backend must still call {@link Sink#onFinished} once it has. */
    void terminate();

    /** Release resources after the session has finished. Must be safe to call more than once. */
    void close();
}
