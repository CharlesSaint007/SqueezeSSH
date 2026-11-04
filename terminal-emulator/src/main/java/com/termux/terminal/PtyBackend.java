package com.termux.terminal;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;

/**
 * A local subprocess on a pseudo-terminal, created through {@link JNI}. This is the I/O code upstream Termux
 * keeps inside {@link TerminalSession}, moved here unchanged in behaviour.
 */
public final class PtyBackend implements TerminalBackend {

    private static final String LOG_TAG = "PtyBackend";

    private final String mShellPath;
    private final String mCwd;
    private final String[] mArgs;
    private final String[] mEnv;

    /** The pid of the shell process. 0 if not started and -1 if finished running. */
    private volatile int mShellPid;
    /** The master half of the pseudo-terminal pair; -1 when closed. */
    private int mTerminalFileDescriptor = -1;

    public PtyBackend(String shellPath, String cwd, String[] args, String[] env) {
        mShellPath = shellPath;
        mCwd = cwd;
        mArgs = args;
        mEnv = env;
    }

    public int getPid() {
        return mShellPid;
    }

    @Override
    public void start(int columns, int rows, int cellWidthPixels, int cellHeightPixels, Sink sink) {
        int[] processId = new int[1];
        mTerminalFileDescriptor = JNI.createSubprocess(mShellPath, mCwd, mArgs, mEnv, processId, rows, columns, cellWidthPixels, cellHeightPixels);
        mShellPid = processId[0];

        final FileDescriptor terminalFileDescriptorWrapped = wrapFileDescriptor(mTerminalFileDescriptor);

        new Thread("TermSessionInputReader[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                try (InputStream termIn = new FileInputStream(terminalFileDescriptorWrapped)) {
                    final byte[] buffer = new byte[4096];
                    while (true) {
                        int read = termIn.read(buffer);
                        if (read == -1) return;
                        if (!sink.onOutput(buffer, 0, read)) return;
                    }
                } catch (Exception e) {
                    // Ignore, just shutting down.
                }
            }
        }.start();

        new Thread("TermSessionOutputWriter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try (FileOutputStream termOut = new FileOutputStream(terminalFileDescriptorWrapped)) {
                    while (true) {
                        int bytesToWrite = sink.readInput(buffer);
                        if (bytesToWrite == -1) return;
                        termOut.write(buffer, 0, bytesToWrite);
                    }
                } catch (IOException e) {
                    // Ignore.
                }
            }
        }.start();

        new Thread("TermSessionWaiter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                int processExitCode = JNI.waitFor(mShellPid);
                mShellPid = -1;
                sink.onFinished(processExitCode, null);
            }
        }.start();
    }

    @Override
    public void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mTerminalFileDescriptor >= 0)
            JNI.setPtyWindowSize(mTerminalFileDescriptor, rows, columns, cellWidthPixels, cellHeightPixels);
    }

    /** Send SIGKILL to the shell. */
    @Override
    public void terminate() {
        int pid = mShellPid;
        if (pid > 0) {
            try {
                Os.kill(pid, OsConstants.SIGKILL);
            } catch (ErrnoException e) {
                Log.w(LOG_TAG, "Failed sending SIGKILL: " + e.getMessage());
            }
        }
    }

    @Override
    public synchronized void close() {
        if (mTerminalFileDescriptor >= 0) {
            JNI.close(mTerminalFileDescriptor);
            mTerminalFileDescriptor = -1;
        }
    }

    private static FileDescriptor wrapFileDescriptor(int fileDescriptor) {
        FileDescriptor result = new FileDescriptor();
        try {
            Field descriptorField;
            try {
                descriptorField = FileDescriptor.class.getDeclaredField("descriptor");
            } catch (NoSuchFieldException e) {
                // For desktop java:
                descriptorField = FileDescriptor.class.getDeclaredField("fd");
            }
            descriptorField.setAccessible(true);
            descriptorField.set(result, fileDescriptor);
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
            throw new IllegalStateException("Error accessing FileDescriptor#descriptor private field", e);
        }
        return result;
    }
}
