package com.hermesvox.speech;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class PcmStreamTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        CountDownLatch firstWrite = new CountDownLatch(1);
        AtomicBoolean drained = new AtomicBoolean();
        PcmStream.Sink sink = new PcmStream.Sink() {
            public void write(byte[] bytes, int length) {
                check(length % 2 == 0);
                audio.write(bytes, 0, length);
                firstWrite.countDown();
            }
            public void drain() { drained.set(true); }
            public void cancel() {}
        };
        AtomicInteger opened = new AtomicInteger();
        PcmStream stream = new PcmStream(chunk -> {
            opened.incrementAndGet();
            return new InputStream() {
                int pos;
                public int read() { throw new AssertionError("Use bulk read"); }
                public int read(byte[] b, int off, int len) throws IOException {
                    if (pos == 6) {
                        try { if (!firstWrite.await(2, TimeUnit.SECONDS)) throw new IOException("Buffered to EOF"); }
                        catch (InterruptedException e) { throw new IOException(e); }
                        return -1;
                    }
                    int n = Math.min(3, 6 - pos); // deliberately odd reads
                    for (int i = 0; i < n; i++) b[off + i] = (byte) (pos++ + Integer.parseInt(chunk));
                    return n;
                }
            };
        }, sink);
        stream.play(List.of("0", "10"));
        check(opened.get() == 2);
        check(java.util.Arrays.equals(audio.toByteArray(), new byte[] {0,1,2,3,4,5,10,11,12,13,14,15}));
        check(drained.get());
        for (byte[] invalid : new byte[][] {new byte[0], new byte[] {1,2,3}}) {
            AtomicBoolean badDrain = new AtomicBoolean();
            PcmStream broken = new PcmStream(c -> new ByteArrayInputStream(invalid), new PcmStream.Sink() {
                public void write(byte[] b, int n) {}
                public void drain() { badDrain.set(true); }
                public void cancel() {}
            });
            try { broken.play(List.of("x")); throw new AssertionError("Expected invalid PCM failure"); }
            catch (IOException expected) { check(!badDrain.get()); }
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        PcmStream stopped = new PcmStream(c -> { throw new AssertionError("Cancelled stream opened request"); },
            new PcmStream.Sink() {
                public void write(byte[] b, int n) { throw new AssertionError("Late audio"); }
                public void drain() { throw new AssertionError("Cancelled drain"); }
                public void cancel() { cancelled.set(true); }
            });
        stopped.cancel();
        try { stopped.play(List.of("x")); throw new AssertionError("Expected cancellation"); }
        catch (IOException expected) { check(cancelled.get()); }
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean closedInput = new AtomicBoolean();
        PcmStream slowClose = new PcmStream(c -> new InputStream() {
            public int read() throws IOException {
                blocked.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return -1;
            }
            public void close() {
                closedInput.set(true);
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }, new PcmStream.Sink() {
            public void write(byte[] b, int n) {}
            public void drain() { throw new AssertionError("Cancelled drain"); }
            public void cancel() {}
        });
        Thread consumer = new Thread(() -> {
            try { slowClose.play(List.of("x")); } catch (IOException expected) { /* cancellation */ }
        });
        consumer.start();
        check(blocked.await(2, TimeUnit.SECONDS));
        Thread stopper = new Thread(slowClose::cancel);
        stopper.start();
        stopper.join(250);
        boolean immediate = !stopper.isAlive();
        release.countDown();
        stopper.join(2000);
        consumer.join(2000);
        check(immediate); // Stop must not wait on a network stream's blocking close
        check(!consumer.isAlive());
        CountDownLatch thirdOpen = new CountDownLatch(1);
        CountDownLatch nextOpen = new CountDownLatch(1);
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch allowWrite = new CountDownLatch(1);
        AtomicBoolean drainAfterWrites = new AtomicBoolean();
        AtomicInteger writes = new AtomicInteger();
        PcmStream prefetch = new PcmStream(c -> {
            if (c.equals("2")) nextOpen.countDown();
            if (c.equals("3")) thirdOpen.countDown();
            return new ByteArrayInputStream(new byte[] {1,2});
        }, new PcmStream.Sink() {
            public void write(byte[] b, int n) throws IOException {
                writing.countDown();
                try { allowWrite.await(); } catch (InterruptedException e) { throw new IOException(e); }
                writes.incrementAndGet();
            }
            public void drain() { drainAfterWrites.set(writes.get() == 3); }
            public void cancel() { allowWrite.countDown(); }
        });
        Thread prefetchConsumer = new Thread(() -> {
            try { prefetch.play(List.of("1", "2", "3")); }
            catch (IOException failure) { throw new AssertionError(failure); }
        });
        prefetchConsumer.start();
        check(writing.await(2, TimeUnit.SECONDS));
        check(nextOpen.await(2, TimeUnit.SECONDS)); // next fetched while current output still writing
        check(!thirdOpen.await(100, TimeUnit.MILLISECONDS)); // not an unbounded request lookahead
        check(!drainAfterWrites.get());
        allowWrite.countDown();
        prefetchConsumer.join(2000);
        check(!prefetchConsumer.isAlive());
        check(drainAfterWrites.get());
        AtomicBoolean lateClosed = new AtomicBoolean();
        PcmStream[] race = new PcmStream[1];
        race[0] = new PcmStream(c -> {
            race[0].cancel(); // Stop between open and producer ownership registration
            return new ByteArrayInputStream(new byte[] {1,2}) {
                public void close() { lateClosed.set(true); }
            };
        }, new PcmStream.Sink() {
            public void write(byte[] b, int n) { throw new AssertionError("Stale write"); }
            public void drain() { throw new AssertionError("Stale drain"); }
            public void cancel() {}
        });
        try { race[0].play(List.of("x")); } catch (IOException expected) { /* stopped */ }
        Thread.sleep(100);
        check(lateClosed.get());
        AtomicInteger bufferedReads = new AtomicInteger();
        CountDownLatch bufferFull = new CountDownLatch(1);
        CountDownLatch outputBlocked = new CountDownLatch(1);
        CountDownLatch stopOutput = new CountDownLatch(1);
        PcmStream bounded = new PcmStream(c -> new InputStream() {
            public int read() { throw new AssertionError("bulk only"); }
            public int read(byte[] b, int off, int len) {
                int count = bufferedReads.incrementAndGet();
                if (count == 18) bufferFull.countDown();
                java.util.Arrays.fill(b, off, off + len, (byte) 0);
                return len;
            }
        }, new PcmStream.Sink() {
            public void write(byte[] b, int n) throws IOException {
                outputBlocked.countDown();
                try { stopOutput.await(); } catch (InterruptedException e) { throw new IOException(e); }
            }
            public void drain() { throw new AssertionError("Infinite input must not drain"); }
            public void cancel() { stopOutput.countDown(); }
        });
        Thread boundedConsumer = new Thread(() -> {
            try { bounded.play(List.of("x")); } catch (IOException expected) { /* Stop */ }
        });
        boundedConsumer.start();
        check(outputBlocked.await(2, TimeUnit.SECONDS));
        check(bufferFull.await(2, TimeUnit.SECONDS));
        Thread.sleep(50);
        check(bufferedReads.get() == 18); // 1 in sink + 16 queued + 1 in producer, never whole audio
        bounded.cancel();
        boundedConsumer.join(2000);
        check(!boundedConsumer.isAlive());
        System.out.println("PASS PcmStreamTest: " + checks + " checks (offline)");
    }
}
