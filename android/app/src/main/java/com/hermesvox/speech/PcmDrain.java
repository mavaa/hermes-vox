package com.hermesvox.speech;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Playback-head completion, never write-completion. Clock/sleep injected for real offline tests. */
public final class PcmDrain {
    private PcmDrain() {}
    /** MODE_STREAM can wait for its start threshold on very short clips. At final
     * EOF only, pad to the track capacity with silence and drain that too.
     */
    public static int primingBytes(long submittedFrames, int capacityFrames) {
        return (int) Math.max(0L, capacityFrames - submittedFrames) * 2;
    }
    public interface Pause { void sleep() throws InterruptedException; }
    public static void await(long submitted, LongSupplier playbackHead, BooleanSupplier active,
                             LongSupplier clock, Pause pause) throws IOException {
        long previous = -1;
        long progressAt = clock.getAsLong();
        while (true) {
            if (!active.getAsBoolean()) throw new IOException("Speech stopped");
            long played = playbackHead.getAsLong();
            if (played >= submitted) return;
            if (played != previous) { previous = played; progressAt = clock.getAsLong(); }
            if (clock.getAsLong() - progressAt >= 10_000) throw new IOException("PCM drain stalled");
            try { pause.sleep(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Speech interrupted", interrupted);
            }
        }
    }
}
