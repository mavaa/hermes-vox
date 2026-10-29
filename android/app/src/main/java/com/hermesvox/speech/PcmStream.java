package com.hermesvox.speech;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** One ordered PCM consumer and one HTTP producer. No complete-audio accumulation.
 * At most current + next request, 16 x 4096 queued bytes (~1.37s at 24kHz mono16).
 * Source owns network abort; Sink.cancel must unblock a pending device write/drain.
 */
public final class PcmStream {
    public interface Source { InputStream open(String chunk) throws IOException; }
    public interface Sink {
        void write(byte[] bytes, int length) throws IOException;
        void drain() throws IOException;
        void cancel();
    }
    private static final class Packet {
        final byte[] bytes;
        final IOException error;
        Packet(byte[] bytes, IOException error) { this.bytes = bytes; this.error = error; }
    }
    private final Source source;
    private final Sink sink;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final ArrayBlockingQueue<Packet> queue = new ArrayBlockingQueue<>(16);
    private final Semaphore ahead = new Semaphore(2);
    private volatile Thread producer;
    private volatile InputStream input;

    public PcmStream(Source source, Sink sink) { this.source = source; this.sink = sink; }

    public void play(List<String> chunks) throws IOException {
        checkActive();
        producer = new Thread(() -> produce(chunks), "vox-pcm-http");
        producer.setDaemon(true);
        producer.start();
        try {
            for (int ended = 0; ended < chunks.size();) {
                checkActive();
                Packet packet = queue.take();
                checkActive();
                if (packet.error != null) throw packet.error;
                if (packet.bytes == null) { ended++; ahead.release(); }
                else sink.write(packet.bytes, packet.bytes.length);
            }
            checkActive();
            sink.drain(); // only complete after hardware playback head reaches all submitted frames
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Speech interrupted", interrupted);
        } finally { cancel(); }
    }

    private void produce(List<String> chunks) {
        try {
            for (String chunk : chunks) {
                ahead.acquire();
                checkActive();
                InputStream opened = source.open(chunk);
                input = opened;
                try (InputStream response = opened) {
                    checkActive();
                    byte[] buffer = new byte[4096];
                    int carry = 0;
                    long total = 0;
                    while (true) {
                        checkActive();
                        int n = response.read(buffer, carry, buffer.length - carry);
                        if (n < 0) break;
                        if (n == 0) continue;
                        total += n;
                        if (total > 28_800_000) throw new IOException("PCM response too large");
                        int available = carry + n;
                        int even = available & ~1;
                        if (even > 0) queue.put(new Packet(java.util.Arrays.copyOf(buffer, even), null));
                        carry = available - even;
                        if (carry != 0) buffer[0] = buffer[even];
                    }
                    if (carry != 0 || total == 0) throw new IOException("Empty or unaligned PCM response");
                } finally { input = null; }
                queue.put(new Packet(null, null));
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (Exception failure) {
            if (!cancelled.get()) {
                try { queue.put(new Packet(null, new IOException("Speech stream failed", failure))); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private void checkActive() throws IOException {
        if (cancelled.get()) throw new IOException("Speech stopped");
    }
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) return;
        sink.cancel();
        Thread worker = producer;
        if (worker != null) worker.interrupt();
        // Wake a consumer waiting for a stalled source, without waiting on network close.
        queue.clear();
        queue.offer(new Packet(null, new IOException("Speech stopped")));
        InputStream response = input;
        if (response != null) {
            Thread closer = new Thread(() -> {
                try { response.close(); } catch (IOException ignored) { /* source also aborts connection */ }
            }, "vox-pcm-close");
            closer.setDaemon(true);
            closer.start();
        }
    }
}
