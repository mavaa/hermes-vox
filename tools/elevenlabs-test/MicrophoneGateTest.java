package com.hermesvox.speech;

/** Deterministic tests of the production microphone ownership/admission seam. */
public final class MicrophoneGateTest {
    private static int checks;
    private static final class Capture implements AutoCloseable {
        boolean capturing = true;
        int closes;
        @Override public void close() { capturing = false; closes++; }
    }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            if (!latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("race timed out");
        } catch (InterruptedException e) { throw new AssertionError(e); }
    }
    private static void join(Thread thread) {
        try { thread.join(2000); } catch (InterruptedException e) { throw new AssertionError(e); }
        check(!thread.isAlive(), "worker completed");
    }
    private static void delayedTranscription() {
        MicrophoneGate<Capture> gate = new MicrophoneGate<>();
        long epoch = gate.epoch();
        Capture capture = gate.acquire(epoch, Capture::new);
        var decoding = new java.util.concurrent.CountDownLatch(1);
        var finish = new java.util.concurrent.CountDownLatch(1);
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        Thread stt = new Thread(() -> {
            decoding.countDown();
            await(finish);
            gate.dispatch(epoch, requests::incrementAndGet); // final or partial STT callback
        });
        stt.start(); await(decoding);
        gate.setMuted(true);
        check(!capture.capturing, "mute releases capture while STT is blocked");
        gate.setMuted(false);
        Capture fresh = gate.acquire(gate.epoch(), Capture::new);
        finish.countDown(); join(stt);
        check(requests.get() == 0 && fresh.capturing, "late old STT neither submits nor closes newer capture");
        gate.setMuted(true);
        gate.invalidate(); // focus/controller retirement must retain the latch
        for (int i = 0; i < 10; i++)
            check(gate.acquire(gate.epoch(), Capture::new) == null, "automatic rearm stays closed");
    }
    private static void transcriptionAdmission() {
        MicrophoneGate<Capture> gate = new MicrophoneGate<>();
        long epoch = gate.epoch();
        Capture capture = gate.acquire(epoch, Capture::new);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        // Both queued partial and final invocations arrive only after mute/unmute.
        gate.setMuted(true);
        gate.setMuted(false);
        for (String kind : java.util.List.of("partial", "final", "barge")) {
            String result = gate.invoke(epoch, () -> { calls.incrementAndGet(); return kind; });
            check(result == null, "stale " + kind + " invocation not admitted");
        }
        check(calls.get() == 0, "stale snapshots never invoke the STT backend");
        check(!capture.capturing, "old hardware released");
        Capture fresh = gate.acquire(gate.epoch(), Capture::new);
        long freshEpoch = gate.epoch();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var finish = new java.util.concurrent.CountDownLatch(1);
        var muted = new java.util.concurrent.CountDownLatch(1);
        var result = new java.util.concurrent.atomic.AtomicReference<String>();
        Thread stt = new Thread(() -> result.set(gate.invoke(freshEpoch, () -> {
            calls.incrementAndGet(); entered.countDown(); await(finish); return "decoded";
        })));
        stt.start(); await(entered);
        Thread mute = new Thread(() -> { gate.setMuted(true); muted.countDown(); });
        mute.start();
        try {
            await(muted);
            check(!fresh.capturing, "mute releases hardware without waiting for admitted STT");
        } finally { finish.countDown(); }
        join(mute); join(stt);
        check("decoded".equals(result.get()) && calls.get() == 1, "already admitted STT may finish exactly once");
        check(!gate.dispatch(freshEpoch, () -> { throw new AssertionError("old result submitted"); }),
                "admitted STT result remains fenced after mute");
    }

    private static void playbackContinues() {
        MicrophoneGate<Capture> gate = new MicrophoneGate<>();
        Capture capture = gate.acquire(gate.epoch(), Capture::new);
        var outputStarted = new java.util.concurrent.CountDownLatch(1);
        var finish = new java.util.concurrent.CountDownLatch(1);
        var drained = new java.util.concurrent.atomic.AtomicBoolean();
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        PcmStream output = new PcmStream(c -> new java.io.ByteArrayInputStream(new byte[] {1, 2}), new PcmStream.Sink() {
            public void write(byte[] bytes, int length) { outputStarted.countDown(); await(finish); }
            public void drain() { drained.set(true); }
            public void cancel() { cancelled.set(true); }
        });
        Thread playback = new Thread(() -> {
            try { output.play(java.util.List.of("reply")); } catch (Throwable t) { failure.set(t); }
        });
        playback.start(); await(outputStarted);
        gate.setMuted(true);
        check(!capture.capturing && !cancelled.get(), "mute closes input, not active production PCM output");
        finish.countDown(); join(playback);
        check(failure.get() == null && drained.get(), "active reply drains normally while muted");
        check(gate.isMuted() && gate.acquire(gate.epoch(), Capture::new) == null, "playback completion never unmutes");
    }

    public static void main(String[] args) throws java.io.IOException {
        MicrophoneGate<Capture> gate = new MicrophoneGate<>();
        long epoch = gate.epoch();
        Capture capture = gate.acquire(epoch, Capture::new);
        check(capture != null && capture.capturing, "capture starts");
        gate.setMuted(true);
        check(!capture.capturing && capture.closes == 1, "mute releases live microphone exactly once");
        check(gate.acquire(gate.epoch(), Capture::new) == null, "muted cannot acquire microphone");
        gate.setMuted(false);
        check(!gate.isCurrent(epoch), "unmute never admits old final or partial transcription");
        int[] requests = {0};
        check(!gate.dispatch(epoch, () -> requests[0]++), "queued pre-mute agent request fenced");
        long fresh = gate.epoch();
        Capture resumed = gate.acquire(fresh, Capture::new);
        check(resumed != null && resumed != capture, "explicit unmute starts a clean capture");
        check(gate.dispatch(fresh, () -> requests[0]++), "fresh transcript admitted");
        gate.invalidate();
        check(!resumed.capturing && !gate.dispatch(fresh, () -> requests[0]++), "controller teardown fences queued results");
        check(requests[0] == 1, "only the new utterance reaches agent");
        MicrophoneGate<Capture> opening = new MicrophoneGate<>();
        Capture lateOpen = new Capture();
        Capture rejected = opening.acquire(opening.epoch(), () -> {
            opening.setMuted(true); // synchronous permission/device callback during open
            return lateOpen;
        });
        check(rejected == null && !lateOpen.capturing, "mute during acquisition closes late hardware");
        delayedTranscription();
        transcriptionAdmission();
        playbackContinues();
        String controller = java.nio.file.Files.readString(java.nio.file.Path.of(args[0]));
        for (String invocation : java.util.List.of(
                "microphone.invoke(captureEpoch) { stt?.transcribe(snap, sr) }",
                "microphone.invoke(captureEpoch) { stt?.transcribeWhole(seg.toFloatArray(), sr) }",
                "microphone.invoke(bargeCaptureEpoch) { stt?.transcribeWhole(segSnapshot, 16000)?.trim() }"))
            check(controller.contains(invocation), "production STT call must use invocation admission: " + invocation);
        System.out.println("PASS MicrophoneGateTest: " + checks + " checks (offline)");
    }
}
