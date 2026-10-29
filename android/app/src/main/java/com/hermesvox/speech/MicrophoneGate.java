package com.hermesvox.speech;

import java.util.function.Supplier;

/** Capture-only ownership. Never touches the agent, playback or their turn epochs. */
public final class MicrophoneGate<T extends AutoCloseable> {
    private boolean muted;
    private long epoch;
    private T capture;

    public synchronized long epoch() { return epoch; }
    public synchronized boolean isMuted() { return muted; }
    public synchronized boolean isCurrent(long token) { return !muted && token == epoch; }

    public synchronized void setMuted(boolean value) {
        if (muted == value) return;
        muted = value;
        epoch++;
        release();
    }

    /** Opening and muting serialize: an in-progress open cannot resurrect a muted mic. */
    public synchronized T acquire(long token, Supplier<T> open) {
        if (!isCurrent(token)) return null;
        if (capture == null) {
            T opened = open.get();
            if (!isCurrent(token)) {
                close(opened);
                return null;
            }
            capture = opened;
        }
        return capture;
    }

    /** Atomically admit a queued result against mute; action must be a short hand-off. */
    public synchronized boolean dispatch(long token, Runnable action) {
        if (!isCurrent(token)) return false;
        action.run();
        return true;
    }

    /**
     * Admit a blocking STT invocation, not merely its returned text. The synchronized
     * decision is its start/linearization point: after admission it is in flight and
     * may finish across mute. Never hold the ownership lock during backend/network
     * work; mute must still release hardware immediately. Callers must also fence
     * the returned result with dispatch before handing it to the agent.
     */
    public <R> R invoke(long token, Supplier<R> operation) {
        synchronized (this) {
            if (!isCurrent(token)) return null;
        }
        return operation.get();
    }

    /** Retire one controller/capture generation without changing the call's mute latch. */
    public synchronized void invalidate() {
        epoch++;
        release();
    }

    private void release() {
        T old = capture;
        capture = null;
        close(old);
    }

    private void close(T value) {
        if (value != null) {
            try { value.close(); } catch (Exception ignored) { /* admission remains closed */ }
        }
    }
}
