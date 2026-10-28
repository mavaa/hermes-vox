package com.hermesvox.speech;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class PcmDrainTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    public static void main(String[] args) throws Exception {
        check(PcmDrain.primingBytes(20, 4800) == 9560);
        check(PcmDrain.primingBytes(4800, 4800) == 0);
        check(PcmDrain.primingBytes(9600, 4800) == 0);
        AtomicLong clock = new AtomicLong();
        AtomicInteger polls = new AtomicInteger();
        PcmDrain.await(100, () -> polls.incrementAndGet() * 25L, () -> true,
            clock::get, () -> clock.addAndGet(10));
        check(polls.get() == 4); // no stop at just submitted, or first nonzero hardware head
        check(clock.get() == 30);
        polls.set(0);
        try {
            PcmDrain.await(100, () -> { polls.incrementAndGet(); return 0L; }, () -> polls.get() < 3,
                clock::get, () -> clock.addAndGet(10));
            throw new AssertionError("Expected Stop during drain");
        } catch (IOException expected) { check(polls.get() == 3); }
        clock.set(0);
        try {
            PcmDrain.await(100, () -> 0L, () -> true, clock::get, () -> clock.addAndGet(1000));
            throw new AssertionError("Expected output stall timeout");
        } catch (IOException expected) { check(clock.get() >= 10_000 && clock.get() <= 11_000); }
        System.out.println("PASS PcmDrainTest: " + checks + " checks (offline)");
    }
}
