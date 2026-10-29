package com.hermesvox.speech;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Main-queue rearm admission shared by gate-release and worker-idle transitions. */
public final class PlatformRearm {
    private PlatformRearm() {}

    public static void post(MicrophoneGate<?> microphone, long captureEpoch,
                            BooleanSupplier eligible, Consumer<Runnable> main,
                            Runnable listen) {
        main.accept(() -> microphone.dispatch(captureEpoch, () -> {
            if (eligible.getAsBoolean()) listen.run();
        }));
    }
}
