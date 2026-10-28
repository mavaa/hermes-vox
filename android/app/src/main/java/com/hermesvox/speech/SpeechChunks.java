package com.hermesvox.speech;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Lossless completed-reply splitting. Limit is conservative UTF-16 units (<= code-point cap).
 * Bracketed speech cues are atomic. Malformed Unicode/cues or a cue exceeding the limit
 * fail before any paid request, rather than silently dropping or rewriting content.
 */
public final class SpeechChunks {
    public static final int LIMIT = 2000;
    private SpeechChunks() {}

    public static List<String> split(String text) {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("Empty speech");
        boolean[] safe = new boolean[text.length() + 1];
        safe[0] = true;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 == text.length() || !Character.isLowSurrogate(text.charAt(i + 1)))
                    throw new IllegalArgumentException("Malformed speech Unicode");
                i++;
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Malformed speech Unicode");
            else if (c == '[') depth++;
            else if (c == ']') {
                if (--depth < 0) throw new IllegalArgumentException("Unbalanced speech cue");
            }
            safe[i + 1] = depth == 0;
        }
        if (depth != 0) throw new IllegalArgumentException("Unclosed speech cue");
        List<String> result = new ArrayList<>();
        int start = 0;
        while (text.length() - start > LIMIT) {
            int hard = 0, word = 0, sentence = 0;
            boolean hasText = false;
            for (int end = start + 1; end <= start + LIMIT; end++) {
                if (text.charAt(end - 1) > ' ') hasText = true;
                if (!safe[end] || !hasText) continue;
                hard = end;
                char c = text.charAt(end - 1);
                if (Character.isWhitespace(c)) {
                    word = end;
                    int before = end - 2;
                    while (before >= start && Character.isWhitespace(text.charAt(before))) before--;
                    if (c == '\n' || (before >= start && ".!?。！？".indexOf(text.charAt(before)) >= 0))
                        sentence = end;
                }
            }
            int end = sentence > start ? sentence : word > start ? word : hard;
            // Never manufacture a whitespace-only paid request at the end. Move a
            // complete code point/cue into that final chunk without altering bytes.
            if (end > start && text.substring(end).trim().isEmpty()) {
                do { end--; } while (end > start && !safe[end]);
            }
            if (end <= start || text.substring(start, end).trim().isEmpty())
                throw new IllegalArgumentException("Speech cannot be split losslessly within 2000 characters");
            result.add(text.substring(start, end));
            start = end;
        }
        result.add(text.substring(start));
        return Collections.unmodifiableList(result);
    }
}
