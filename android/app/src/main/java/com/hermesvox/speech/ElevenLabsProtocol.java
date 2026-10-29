package com.hermesvox.speech;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** ElevenLabs batch speech wire contract. No agent, Android, or provider SDK dependency. */
public final class ElevenLabsProtocol {
    private ElevenLabsProtocol() {}

    public static final class Config {
        public final String apiKey;
        public final String sttModel;
        public final String ttsModel;
        public final String voiceId;
        public Config(String apiKey, String sttModel, String ttsModel, String voiceId) {
            this.apiKey = apiKey;
            this.sttModel = sttModel;
            this.ttsModel = ttsModel;
            this.voiceId = voiceId;
        }
    }

    public static final class Request {
        public final String url;
        public final Map<String, String> headers;
        public final byte[] body;
        private Request(String url, String key, String contentType, byte[] body) {
            this.url = url;
            Map<String, String> values = new LinkedHashMap<>();
            values.put("xi-api-key", key);
            values.put("Content-Type", contentType);
            this.headers = Collections.unmodifiableMap(values);
            this.body = body;
        }
    }

    public static Request transcription(Config config, byte[] wav, String boundary) {
        requireToken(config.sttModel, "STT model");
        requireKey(config.apiKey);
        requireToken(boundary, "multipart boundary");
        if (wav == null || wav.length == 0 || wav.length > 1_048_576)
            throw new IllegalArgumentException("Audio must be 1..1048576 bytes");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        utf8(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"model_id\"\r\n\r\n"
            + config.sttModel + "\r\n");
        utf8(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"tag_audio_events\"\r\n\r\nfalse\r\n");
        utf8(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"utterance.wav\"\r\n"
            + "Content-Type: audio/wav\r\n\r\n");
        out.write(wav, 0, wav.length);
        utf8(out, "\r\n--" + boundary + "--\r\n");
        return new Request("https://api.elevenlabs.io/v1/speech-to-text", config.apiKey,
            "multipart/form-data; boundary=" + boundary, out.toByteArray());
    }

    public static Request speech(Config config, String text) {
        requireKey(config.apiKey);
        requireToken(config.ttsModel, "TTS model");
        requireToken(config.voiceId, "voice ID");
        boolean dialogue = config.ttsModel.equals("eleven_v4") || config.ttsModel.equals("eleven_v4_turbo");
        int limit = dialogue ? 2000 : 5000;
        if (text == null || text.trim().isEmpty() || text.length() > limit)
            throw new IllegalArgumentException("Speech text must be 1.." + limit + " characters");
        String endpoint = dialogue ? "https://api.elevenlabs.io/v1/text-to-dialogue"
            : "https://api.elevenlabs.io/v1/text-to-speech/" + config.voiceId;
        String payload = dialogue
            ? "{\"inputs\":[{\"text\":" + quote(text) + ",\"voice_id\":" + quote(config.voiceId)
                + "}],\"model_id\":" + quote(config.ttsModel) + "}"
            : "{\"text\":" + quote(text) + ",\"model_id\":" + quote(config.ttsModel) + "}";
        return new Request(endpoint + "?output_format=mp3_44100_128", config.apiKey,
            "application/json", payload.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean isDialogue(Config config) {
        return "eleven_v4".equals(config.ttsModel) || "eleven_v4_turbo".equals(config.ttsModel);
    }

    /** Same explicit voice/model payload as batch, but incremental raw mono PCM16 at 24kHz. */
    public static Request streamSpeech(Config config, String text) {
        if (!isDialogue(config)) throw new IllegalArgumentException("Dialogue streaming requires a v4 model");
        Request batch = speech(config, text);
        return new Request("https://api.elevenlabs.io/v1/text-to-dialogue/stream?output_format=pcm_24000",
            config.apiKey, "application/json", batch.body);
    }

    /** Fail closed: never fall back to plaintext if Android Keystore encryption fails. */
    public static boolean storeKey(String plaintext, java.util.function.Function<String, String> encrypt,
                                   java.util.function.Consumer<String> persist) {
        final String sealed;
        if (plaintext.isEmpty()) sealed = "";
        else {
            try {
                requireKey(plaintext);
                sealed = encrypt.apply(plaintext);
            } catch (RuntimeException failure) { return false; }
            if (sealed == null || sealed.isEmpty() || sealed.equals(plaintext)) return false;
        }
        persist.accept(sealed);
        return true;
    }

    private static void requireKey(String key) {
        if (key == null || !key.matches("[\\x21-\\x7e]{1,512}"))
            throw new IllegalArgumentException("A printable API key is required");
    }

    private static void requireToken(String value, String label) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException(label + " is required (letters, digits, underscore, hyphen)");
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static void utf8(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
    }
}
