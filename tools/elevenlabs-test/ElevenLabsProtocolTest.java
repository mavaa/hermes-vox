package com.hermesvox.speech;

import java.nio.charset.StandardCharsets;

/** Dependency-free tests of the production Java consumed by Android. No provider calls. */
public final class ElevenLabsProtocolTest {
    private static int checks;
    private static void equal(Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    public static void main(String[] args) throws Exception {
        ElevenLabsProtocol.Config config = new ElevenLabsProtocol.Config(
            "offline-test-key", "scribe_v2", "selected_model_v4", "voice_123");
        ElevenLabsProtocol.Request request = ElevenLabsProtocol.transcription(
            config, new byte[] {82, 73, 70, 70}, "vox-boundary");
        equal("https://api.elevenlabs.io/v1/speech-to-text", request.url);
        equal("offline-test-key", request.headers.get("xi-api-key"));
        equal(false, request.headers.containsKey("Authorization"));
        equal("multipart/form-data; boundary=vox-boundary", request.headers.get("Content-Type"));
        equal("--vox-boundary\r\nContent-Disposition: form-data; name=\"model_id\"\r\n\r\nscribe_v2\r\n"
            + "--vox-boundary\r\nContent-Disposition: form-data; name=\"tag_audio_events\"\r\n\r\nfalse\r\n"
            + "--vox-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"utterance.wav\"\r\n"
            + "Content-Type: audio/wav\r\n\r\nRIFF\r\n--vox-boundary--\r\n",
            new String(request.body, StandardCharsets.UTF_8));
        ElevenLabsProtocol.Request speech = ElevenLabsProtocol.speech(config, "Say \"hi\"\n\\\t\u0001 — 🌸");
        equal("https://api.elevenlabs.io/v1/text-to-speech/voice_123?output_format=mp3_44100_128", speech.url);
        equal("application/json", speech.headers.get("Content-Type"));
        equal("offline-test-key", speech.headers.get("xi-api-key"));
        equal("{\"text\":\"Say \\\"hi\\\"\\n\\\\\\t\\u0001 — 🌸\",\"model_id\":\"selected_model_v4\"}",
            new String(speech.body, StandardCharsets.UTF_8));
        rejects(() -> ElevenLabsProtocol.speech(new ElevenLabsProtocol.Config("key", "scribe_v2", "", "voice"), "text"));
        rejects(() -> ElevenLabsProtocol.speech(new ElevenLabsProtocol.Config("key", "scribe_v2", "model", "../other?x=y"), "text"));
        rejects(() -> ElevenLabsProtocol.speech(new ElevenLabsProtocol.Config("key\r\nInjected: yes", "scribe_v2", "model", "voice"), "text"));
        rejects(() -> ElevenLabsProtocol.speech(config, " "));
        rejects(() -> ElevenLabsProtocol.transcription(config, new byte[0], "boundary"));
        rejects(() -> ElevenLabsProtocol.transcription(config, new byte[] {1}, "x\r\nInjected"));
        rejects(() -> ElevenLabsProtocol.transcription(new ElevenLabsProtocol.Config("", "scribe_v2", "", ""), new byte[] {1}, "boundary"));
        rejects(() -> ElevenLabsProtocol.transcription(new ElevenLabsProtocol.Config("key", "scribe_v2\r\n", "", ""), new byte[] {1}, "boundary"));
        // Independent legs: transcription must NOT require a TTS voice/model.
        equal(request.url, ElevenLabsProtocol.transcription(
            new ElevenLabsProtocol.Config("key", "scribe_v2", "", ""), new byte[] {1}, "boundary").url);
        java.util.concurrent.atomic.AtomicReference<byte[]> stored = new java.util.concurrent.atomic.AtomicReference<>();
        equal(true, ElevenLabsProtocol.storeKey("offline-key", value -> "sealed:" + value,
            value -> stored.set(value.getBytes(StandardCharsets.UTF_8))));
        equal("sealed:offline-key", new String(stored.get(), StandardCharsets.UTF_8));
        equal(false, ElevenLabsProtocol.storeKey("new-key", value -> null,
            value -> { throw new AssertionError("Encryption failure must not write"); }));
        equal(false, ElevenLabsProtocol.storeKey("new-key", value -> value,
            value -> { throw new AssertionError("Plaintext must not be stored"); }));
        equal(false, ElevenLabsProtocol.storeKey("new-key", value -> { throw new IllegalStateException(); },
            value -> { throw new AssertionError("Encryption error must not write"); }));
        equal(true, ElevenLabsProtocol.storeKey("", value -> { throw new AssertionError("Deletion needs no encryption"); },
            value -> equal("", value)));
        rejects(() -> ElevenLabsProtocol.speech(config, "x".repeat(5001)));
        rejects(() -> ElevenLabsProtocol.transcription(config, new byte[1_048_577], "boundary"));
        equal(true, ElevenLabsProtocol.speech(config, "x".repeat(5000)).body.length > 5000);
        byte[] binaryWav = new byte[] {0, (byte) 255, (byte) 128, 13, 10};
        byte[] binaryBody = ElevenLabsProtocol.transcription(config, binaryWav, "binary-boundary").body;
        int binaryStart = new String(binaryBody, StandardCharsets.ISO_8859_1).indexOf("Content-Type: audio/wav\r\n\r\n")
            + "Content-Type: audio/wav\r\n\r\n".length();
        equal(true, java.util.Arrays.equals(binaryWav,
            java.util.Arrays.copyOfRange(binaryBody, binaryStart, binaryStart + binaryWav.length)));
        for (String model : new String[] {"eleven_v4", "eleven_v4_turbo"}) {
            ElevenLabsProtocol.Config v4 = new ElevenLabsProtocol.Config("key", "scribe_v2", model, "voice_123");
            ElevenLabsProtocol.Request dialogue = ElevenLabsProtocol.speech(v4, "Say \"hi\"\n🌸");
            equal("https://api.elevenlabs.io/v1/text-to-dialogue?output_format=mp3_44100_128", dialogue.url);
            equal("{\"inputs\":[{\"text\":\"Say \\\"hi\\\"\\n🌸\",\"voice_id\":\"voice_123\"}],\"model_id\":\"" + model + "\"}",
                new String(dialogue.body, StandardCharsets.UTF_8));
            equal("key", dialogue.headers.get("xi-api-key"));
            rejects(() -> ElevenLabsProtocol.speech(v4, "x".repeat(2001)));
            equal(true, ElevenLabsProtocol.speech(v4, "x".repeat(2000)).body.length > 2000);
        }
        for (String model : new String[] {"eleven_v4", "eleven_v4_turbo"}) {
            ElevenLabsProtocol.Config v4 = new ElevenLabsProtocol.Config("key", "scribe_v2", model, "voice_123");
            ElevenLabsProtocol.Request stream = ElevenLabsProtocol.streamSpeech(v4, "Full reply [laughs] 🌸");
            equal("https://api.elevenlabs.io/v1/text-to-dialogue/stream?output_format=pcm_24000", stream.url);
            equal(new String(ElevenLabsProtocol.speech(v4, "Full reply [laughs] 🌸").body, StandardCharsets.UTF_8),
                new String(stream.body, StandardCharsets.UTF_8));
            equal("key", stream.headers.get("xi-api-key"));
            rejects(() -> ElevenLabsProtocol.streamSpeech(v4, "x".repeat(2001)));
        }
        rejects(() -> ElevenLabsProtocol.streamSpeech(config, "no implicit model downgrade"));
        System.out.println("PASS ElevenLabsProtocolTest: " + checks + " checks (offline)");
    }

    private static void rejects(Runnable action) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid input to be rejected");
    }
}
