package com.aether.gateway.core.domain;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F4 correctness note, mitigation 2: a semantic cache hit must be
 * rejected outright, regardless of similarity score, if the two prompts
 * differ in any extracted number, named entity, or date. This is the
 * direct mitigation for the PRD's named failure mode ("what is 2+2 vs
 * what is 2+3").
 *
 * <p>Entity extraction here is a deliberately simple, in-process,
 * no-network heuristic (capitalized-word runs, minus a stopword list of
 * common sentence-initial words), not a trained NER model - ADR-007
 * scopes Spring AI usage to the embedding model only. This is a
 * precision/recall tradeoff: a genuine entity that also happens to be a
 * common sentence-initial word (e.g. a sentence starting "There...")
 * will be missed. That is an accepted, documented limitation of a
 * guard, not a full entity-recognition system; false negatives here are
 * caught by threshold tuning and the false-hit-rate measurement instead.
 *
 * <p>Also out of scope, measured directly via the M4 threshold sweep:
 * negation/polarity flips ("open" vs "closed") and spelled-out numbers
 * ("two" vs "three") are invisible to this guard, since it only ever
 * compares numbers, dates, and capitalized entities. See
 * docs/design/cache-correctness.md's "Known, accepted limitations"
 * section for the measured impact and why closing these gaps is a
 * Phase 09 follow-up, not required here.
 */
public final class EntityNumericGuard {

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private static final Pattern DATE = Pattern.compile(
            "\\b\\d{4}-\\d{2}-\\d{2}\\b"
                    + "|\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b"
                    + "|\\b(?:January|February|March|April|May|June|July|August|September|October|November|December)"
                    + "\\s+\\d{1,2}(?:st|nd|rd|th)?(?:,?\\s*\\d{4})?\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CAPITALIZED_PHRASE = Pattern.compile("\\b[A-Z][a-zA-Z]+(?:\\s+[A-Z][a-zA-Z]+)*\\b");

    private static final Set<String> SENTENCE_INITIAL_STOPWORDS = Set.of(
            "what", "who", "when", "where", "why", "how", "which",
            "is", "are", "was", "were", "am", "be", "been",
            "do", "does", "did", "can", "could", "would", "should", "will", "shall",
            "the", "a", "an", "this", "that", "these", "those",
            "i", "we", "you", "he", "she", "it", "they",
            "there", "here", "let",
            "please", "tell", "explain", "describe", "give", "show", "list", "summarise", "summarize");

    private EntityNumericGuard() {
    }

    public static boolean sameFingerprint(String promptA, String promptB) {
        return fingerprint(promptA).equals(fingerprint(promptB));
    }

    public static Set<String> fingerprint(String prompt) {
        Set<String> tokens = new LinkedHashSet<>();
        addDates(prompt, tokens);
        String withoutDates = DATE.matcher(prompt).replaceAll(" ");
        addNumbers(withoutDates, tokens);
        addEntities(prompt, tokens);
        return tokens;
    }

    private static void addDates(String text, Set<String> tokens) {
        Matcher matcher = DATE.matcher(text);
        while (matcher.find()) {
            tokens.add("date:" + matcher.group().toLowerCase());
        }
    }

    private static void addNumbers(String text, Set<String> tokens) {
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            tokens.add("num:" + matcher.group());
        }
    }

    private static void addEntities(String text, Set<String> tokens) {
        Matcher matcher = CAPITALIZED_PHRASE.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group();
            if (isSentenceInitialStopword(text, matcher.start(), candidate)) {
                continue;
            }
            tokens.add("entity:" + candidate.toLowerCase());
        }
    }

    private static boolean isSentenceInitialStopword(String text, int startIndex, String candidate) {
        if (candidate.contains(" ")) {
            return false; // multi-word capitalized phrases are never treated as stopwords
        }
        boolean atSentenceStart = startIndex == 0 || precededBySentenceBoundary(text, startIndex);
        return atSentenceStart && SENTENCE_INITIAL_STOPWORDS.contains(candidate.toLowerCase());
    }

    private static boolean precededBySentenceBoundary(String text, int startIndex) {
        int i = startIndex - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
            i--;
        }
        return i < 0 || text.charAt(i) == '.' || text.charAt(i) == '?' || text.charAt(i) == '!';
    }
}
