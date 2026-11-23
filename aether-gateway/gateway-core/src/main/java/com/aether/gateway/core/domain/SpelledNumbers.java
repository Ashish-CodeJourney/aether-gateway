package com.aether.gateway.core.domain;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites spelled-out cardinal numbers ("two", "twenty-one", "one
 * hundred and five", "three million") to digits, so that
 * {@link EntityNumericGuard} compares "two doses" and "2 doses" as the
 * same number and "two doses" and "three doses" as different ones.
 *
 * <p>A lone "one" is left alone: it is far more often a pronoun or
 * determiner ("which one", "one of", "in one paragraph" meaning "a
 * single") than a quantity whose change flips an answer, and treating
 * it as a number measurably rejected genuine paraphrases. It still
 * counts inside a compound ("twenty-one", "one hundred"). Ordinals
 * ("first", "second") are not converted: "second" is also a time unit
 * and "first" is mostly a discourse marker ("first, install..."), so
 * converting them would cost more paraphrase hits than it saves.
 */
final class SpelledNumbers {

    private static final Map<String, Integer> UNITS_AND_TENS = Map.ofEntries(
            Map.entry("zero", 0), Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3),
            Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7),
            Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("eleven", 11),
            Map.entry("twelve", 12), Map.entry("thirteen", 13), Map.entry("fourteen", 14),
            Map.entry("fifteen", 15), Map.entry("sixteen", 16), Map.entry("seventeen", 17),
            Map.entry("eighteen", 18), Map.entry("nineteen", 19), Map.entry("twenty", 20),
            Map.entry("thirty", 30), Map.entry("forty", 40), Map.entry("fifty", 50), Map.entry("sixty", 60),
            Map.entry("seventy", 70), Map.entry("eighty", 80), Map.entry("ninety", 90));

    private static final Map<String, Long> SCALES = Map.of(
            "hundred", 100L, "thousand", 1_000L, "million", 1_000_000L, "billion", 1_000_000_000L);

    private static final Pattern DIGITS_WITH_SCALE = Pattern.compile(
            "(?<![\\w.])(\\d+(?:\\.\\d+)?)\\s+(hundred|thousand|million|billion)(?![a-z])", Pattern.CASE_INSENSITIVE);

    // A plain letter-run scan plus map lookups, rather than one regex
    // alternation over every number word: this runs on every semantic
    // lookup, prompts can be tens of kilobytes, and a case-insensitive
    // alternation tried at every word boundary measured several times
    // slower than tokenizing once.
    private static final Pattern LETTERS = Pattern.compile("[A-Za-z]+");

    private static final Pattern WORD_GAP = Pattern.compile("[\\s-]+");

    private static final Pattern AND_GAP = Pattern.compile("\\s+and\\s+", Pattern.CASE_INSENSITIVE);

    private SpelledNumbers() {
    }

    static String toDigits(String text) {
        return spelledToDigits(scaledDigitsToDigits(text));
    }

    private static String scaledDigitsToDigits(String text) {
        Matcher matcher = DIGITS_WITH_SCALE.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder rewritten = new StringBuilder(text.length());
        do {
            BigDecimal scale = BigDecimal.valueOf(SCALES.get(matcher.group(2).toLowerCase(Locale.ROOT)));
            String value = new BigDecimal(matcher.group(1)).multiply(scale).stripTrailingZeros().toPlainString();
            matcher.appendReplacement(rewritten, value);
        } while (matcher.find());
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private static String spelledToDigits(String text) {
        Matcher letters = LETTERS.matcher(text);
        StringBuilder rewritten = new StringBuilder(text.length());
        int copiedUpTo = 0;
        Run run = null;
        while (letters.find()) {
            String word = letters.group().toLowerCase(Locale.ROOT);
            boolean numberWord = UNITS_AND_TENS.containsKey(word) || SCALES.containsKey(word);
            if (numberWord && run != null && run.continuesWith(text, letters.start())) {
                run = run.extend(word, letters.end());
            } else if (numberWord) {
                copiedUpTo = flush(text, run, rewritten, copiedUpTo);
                run = Run.start(word, letters.start(), letters.end());
            } else if (!word.equals("and")) {
                copiedUpTo = flush(text, run, rewritten, copiedUpTo);
                run = null;
            }
        }
        copiedUpTo = flush(text, run, rewritten, copiedUpTo);
        return copiedUpTo == 0 ? text : rewritten.append(text, copiedUpTo, text.length()).toString();
    }

    private static int flush(String text, Run run, StringBuilder rewritten, int copiedUpTo) {
        if (run == null || run.isLoneOne()) {
            return copiedUpTo;
        }
        rewritten.append(text, copiedUpTo, run.start()).append(run.value());
        return run.end();
    }

    private record Run(int start, int end, String lastWord, long total, long current, int words) {

        static Run start(String word, int start, int end) {
            return new Run(start, end, "", 0, 0, 0).extend(word, end);
        }

        boolean continuesWith(String text, int nextWordStart) {
            CharSequence gap = text.subSequence(end, nextWordStart);
            return WORD_GAP.matcher(gap).matches()
                    || (SCALES.containsKey(lastWord) && AND_GAP.matcher(gap).matches());
        }

        Run extend(String word, int newEnd) {
            if (word.equals("hundred")) {
                return new Run(start, newEnd, word, total, Math.max(current, 1) * 100, words + 1);
            }
            if (SCALES.containsKey(word)) {
                return new Run(start, newEnd, word, total + Math.max(current, 1) * SCALES.get(word), 0, words + 1);
            }
            return new Run(start, newEnd, word, total, current + UNITS_AND_TENS.get(word), words + 1);
        }

        boolean isLoneOne() {
            return words == 1 && lastWord.equals("one");
        }

        String value() {
            return Long.toString(total + current);
        }
    }
}
