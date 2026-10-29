package com.hermesvox.speech;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the production posted-rearm seam without Android's Handler/recognizer. */
public final class PlatformRearmTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws java.io.IOException {
        for (String reason : java.util.List.of("error", "empty")) {
            MicrophoneGate<AutoCloseable> gate = new MicrophoneGate<>();
            ArrayDeque<Runnable> queue = new ArrayDeque<>();
            AtomicBoolean busy = new AtomicBoolean(true);
            AtomicBoolean released = new AtomicBoolean(true);
            AtomicInteger opens = new AtomicInteger();
            Runnable listen = () -> { opens.incrementAndGet(); gate.invalidate(); };
            var ready = (java.util.function.BooleanSupplier) () -> released.get() && !busy.get();
            long epoch = gate.epoch();
            PlatformRearm.post(gate, epoch, ready, queue::add, listen);
            queue.remove().run(); // main queue wins before worker finally
            check(opens.get() == 0, reason + ": release while busy waits");
            busy.set(false);
            PlatformRearm.post(gate, gate.epoch(), ready, queue::add, listen);
            queue.remove().run();
            check(opens.get() == 1, reason + ": worker-idle transition retries recognition");
            // Opposite ordering: both posts queue before main executes; only one opens.
            epoch = gate.epoch();
            PlatformRearm.post(gate, epoch, ready, queue::add, listen);
            PlatformRearm.post(gate, epoch, ready, queue::add, listen);
            queue.remove().run(); queue.remove().run();
            check(opens.get() == 2, reason + ": duplicate queued transitions open only once");
            released.set(false); // reply still owns the gate
            PlatformRearm.post(gate, gate.epoch(), ready, queue::add, listen);
            queue.remove().run();
            check(opens.get() == 2, "worker idle alone cannot interrupt reply");
            released.set(true);
            PlatformRearm.post(gate, gate.epoch(), ready, queue::add, listen);
            gate.setMuted(true); gate.setMuted(false);
            queue.remove().run();
            check(opens.get() == 2, "mute/unmute fences queued rearm");
            PlatformRearm.post(gate, gate.epoch(), ready, queue::add, listen);
            gate.invalidate(); // controller/focus retirement
            queue.remove().run();
            check(opens.get() == 2, "retired capture/controller cannot rearm");
            PlatformRearm.post(gate, gate.epoch(), ready, queue::add, listen);
            released.set(false); // new turn/turn epoch wins before callback
            queue.remove().run();
            check(opens.get() == 2, "stale turn callback checks live eligibility");
        }
        String controller = java.nio.file.Files.readString(java.nio.file.Path.of(args[0]));
        int finallyStart = controller.indexOf("} finally {\n                currentStream = null");
        int finallyEnd = controller.indexOf("}) {", finallyStart);
        check(finallyStart >= 0 && finallyEnd > finallyStart &&
                        controller.substring(finallyStart, finallyEnd).contains("rearmPlatformAfterTurn(gen, gateEpoch)"),
                "production worker finally must retry platform rearm after clearing busy");
        System.out.println("PASS PlatformRearmTest: " + checks + " checks (offline)");
    }
}
