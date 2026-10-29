package com.hermesvox.speech;

import java.util.List;

public final class SpeechChunksTest {
    private static int checks;
    private static void equal(Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    public static void main(String[] args) {
        equal(List.of("x".repeat(1999), "x "), SpeechChunks.split("x".repeat(2000) + " "));
        equal(List.of("  " + "x".repeat(1998), "xx"), SpeechChunks.split("  " + "x".repeat(2000)));
        rejects(" " .repeat(2001) + "x");
        equal(List.of("A complete short reply."), SpeechChunks.split("A complete short reply."));
        equal(List.of("x".repeat(2000)), SpeechChunks.split("x".repeat(2000)));
        String sentence = "a".repeat(1300) + ". ";
        equal(List.of(sentence, "b".repeat(1000)), SpeechChunks.split(sentence + "b".repeat(1000)));
        String word = "a".repeat(1800) + " ";
        equal(List.of(word, "b".repeat(500)), SpeechChunks.split(word + "b".repeat(500)));
        checkLossless("x".repeat(1999) + "🌸" + "y".repeat(2100));
        checkLossless("  A.\n\t" + "word ".repeat(1500) + "  ");
        String cuePrefix = "x".repeat(1990) + " ";
        equal(List.of(cuePrefix, "[softly, with warmth]" + "y".repeat(20)),
            SpeechChunks.split(cuePrefix + "[softly, with warmth]" + "y".repeat(20)));
        checkLossless("x".repeat(1990) + "[laughs]" + "z".repeat(2020));
        rejects("[" + "a".repeat(2000) + "]");
        rejects("hello [unclosed");
        rejects("\ud800");
        rejects("\udc00");
        rejects("");
        rejects("   ");
        // Sentence preference does not impose a minimum or CLI-style 1000-char threshold.
        equal("Hi. ", SpeechChunks.split("Hi. " + "x".repeat(2000)).get(0));
        java.util.Random random = new java.util.Random(42);
        for (int trial = 0; trial < 100; trial++) {
            StringBuilder generated = new StringBuilder("Reply ");
            String[] tokens = {"a", "word ", ". ", "🌸", "[laughs]", "\n"};
            while (generated.length() < 2001 + trial * 50) generated.append(tokens[random.nextInt(tokens.length)]);
            checkLossless(generated.toString());
            for (String part : SpeechChunks.split(generated.toString())) {
                equal(true, !part.trim().isEmpty());
            }
        }
        System.out.println("PASS SpeechChunksTest: " + checks + " checks (offline)");
    }
    private static void checkLossless(String text) {
        List<String> chunks = SpeechChunks.split(text);
        equal(text, String.join("", chunks));
        for (String chunk : chunks) {
            equal(true, !chunk.isEmpty() && chunk.length() <= 2000);
            equal(false, Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)));
            equal(false, Character.isLowSurrogate(chunk.charAt(0)));
            equal(chunk.chars().filter(c -> c == '[').count(), chunk.chars().filter(c -> c == ']').count());
        }
    }
    private static void rejects(String text) {
        checks++;
        try { SpeechChunks.split(text); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid or unsplittable text rejection");
    }
}
