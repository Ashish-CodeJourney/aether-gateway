package com.aether.gateway.core.domain;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * F4 correctness note, mitigation 2: a semantic cache hit must be
 * rejected outright, regardless of similarity score, if the two prompts
 * differ in any fact-bearing token this guard extracts. This is the
 * direct mitigation for the PRD's named failure mode ("what is 2+2 vs
 * what is 2+3").
 *
 * <p>A prompt's fingerprint is the set of:
 * <ul>
 *   <li>numbers, with spelled-out cardinals ("twenty-one") normalised
 *       to digits and digit-grouping commas ("4,200") dropped;</li>
 *   <li>dates (ISO, slash, and named-month forms);</li>
 *   <li>the unit or currency attached to a number ("60 minutes" vs
 *       "60 seconds", "$4,200" vs "€4,200") and the time of day
 *       ("6:45pm" vs "6:45am");</li>
 *   <li>whether the prompt is negated at all ("not", "no", "never",
 *       "without", any "n't" contraction, ...);</li>
 *   <li>which side of a small set of common oppositions it takes
 *       ("open"/"closed", "increased"/"decreased",
 *       "weekdays"/"weekends", ...);</li>
 *   <li>named entities (capitalized-word runs, minus sentence-initial
 *       function words and imperatives such as "What", "In", "Write").</li>
 * </ul>
 *
 * <p>Two prompts are only safe to answer from the same cache entry if
 * their fingerprints are equal. Equality is deliberately the whole
 * rule, rather than a pairwise comparison, because {@code CacheAdapter}
 * persists each entry's fingerprint and compares sets at lookup time:
 * whatever this class can express must survive that round trip. It
 * also means a fact-bearing token present in only one prompt is a
 * mismatch, the same way an added number always has been.
 *
 * <p>Everything here is an in-process, no-network heuristic, not a
 * trained NER or NLI model (ADR-007 scopes Spring AI usage to the
 * embedding model only). The negation check is presence-based, not
 * scope-aware: "not only fast but cheap" counts as negated. The
 * opposition lexicon is short and generic on purpose; a word too common
 * to carry polarity reliably ("more", "close", "drop", "pass") is left
 * out so that paraphrases using it are not rejected. Remaining blind
 * spots (domain nouns such as "finance team" vs "legal team", and any
 * antonym outside the lexicon) are documented in
 * docs/design/cache-correctness.md.
 */
public final class EntityNumericGuard {

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private static final Pattern DIGIT_GROUPING_COMMA = Pattern.compile("(?<=\\d),(?=\\d{3}(?!\\d))");

    // The leading lookahead rejects most positions on one character
    // before the case-insensitive month alternation is attempted; without
    // it this pattern dominated fingerprinting time on long prompts.
    private static final Pattern DATE = Pattern.compile(
            "(?<!\\w)(?=[\\dJFMASOND])(?:\\d{4}-\\d{2}-\\d{2}\\b"
                    + "|\\d{1,2}/\\d{1,2}/\\d{2,4}\\b"
                    + "|(?:January|February|March|April|May|June|July|August|September|October|November|December)"
                    + "\\s+\\d{1,2}(?:st|nd|rd|th)?(?:,?\\s*\\d{4})?\\b)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DATE_NOISE = Pattern.compile("(?<=\\d)(?:st|nd|rd|th)\\b|,");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final Pattern MERIDIEM = Pattern.compile("(?<=\\d)\\s?([ap])\\.?m\\b\\.?", Pattern.CASE_INSENSITIVE);

    private static final Pattern CURRENCY_SYMBOL = Pattern.compile("([$€£¥₹])\\s?(?=\\d)");

    private static final Pattern NUMBER_FOLLOWED_BY_WORD = Pattern.compile("\\d(?:\\s?%|[\\s-]?(°[cf]|[a-z]+)\\b)");

    private static final Pattern WORD = Pattern.compile("[a-z]+(?:'[a-z]+)*");

    private static final Pattern CAPITALIZED_PHRASE = Pattern.compile("\\b[A-Z][a-zA-Z]+(?:\\s+[A-Z][a-zA-Z]+)*\\b");

    private static final Set<String> NEGATIONS = Set.of(
            "not", "no", "never", "none", "nobody", "nothing", "nowhere", "neither", "nor", "without", "cannot",
            "dont", "doesnt", "didnt", "isnt", "arent", "wasnt", "werent", "cant", "wont", "shouldnt", "wouldnt",
            "couldnt", "hasnt", "havent", "hadnt");

    private static final Map<String, String> CURRENCIES = Map.of(
            "$", "usd", "€", "eur", "£", "gbp", "¥", "jpy", "₹", "inr");

    private static final Map<String, String> UNITS = lexicon(Map.ofEntries(
            Map.entry("millisecond", "ms milliseconds millisecond msec"),
            Map.entry("second", "sec secs second seconds"),
            Map.entry("minute", "min mins minute minutes"),
            Map.entry("hour", "hr hrs hour hours"),
            Map.entry("day", "day days"),
            Map.entry("week", "wk wks week weeks"),
            Map.entry("month", "month months"),
            Map.entry("year", "yr yrs year years"),
            Map.entry("decade", "decade decades"),
            Map.entry("millimetre", "mm millimeter millimeters millimetre millimetres"),
            Map.entry("centimetre", "cm centimeter centimeters centimetre centimetres"),
            Map.entry("metre", "meter meters metre metres"),
            Map.entry("kilometre", "km kms kilometer kilometers kilometre kilometres"),
            Map.entry("mile", "mile miles"),
            Map.entry("foot", "ft foot feet"),
            Map.entry("inch", "inch inches"),
            Map.entry("milligram", "mg milligram milligrams"),
            Map.entry("gram", "gram grams"),
            Map.entry("kilogram", "kg kgs kilo kilos kilogram kilograms"),
            Map.entry("pound", "lb lbs pound pounds"),
            Map.entry("ounce", "oz ounce ounces"),
            Map.entry("tonne", "ton tons tonne tonnes"),
            Map.entry("millilitre", "ml milliliter milliliters millilitre millilitres"),
            Map.entry("litre", "liter liters litre litres"),
            Map.entry("gallon", "gallon gallons"),
            Map.entry("cup", "cup cups"),
            Map.entry("tablespoon", "tbsp tablespoon tablespoons"),
            Map.entry("teaspoon", "tsp teaspoon teaspoons"),
            Map.entry("kilobyte", "kb kilobyte kilobytes"),
            Map.entry("megabyte", "mb megabyte megabytes"),
            Map.entry("gigabyte", "gb gigabyte gigabytes"),
            Map.entry("terabyte", "tb terabyte terabytes"),
            Map.entry("percent", "percent pct"),
            Map.entry("celsius", "°c"),
            Map.entry("fahrenheit", "°f"),
            Map.entry("currency:usd", "usd dollar dollars"),
            Map.entry("currency:eur", "eur euro euros"),
            Map.entry("currency:gbp", "gbp")));

    private static final Map<String, String> OPPOSITIONS = lexicon(Map.ofEntries(
            Map.entry("open", "open opens opened opening"),
            Map.entry("closed", "closed closes closing shut shuts"),
            Map.entry("increase", "increase increases increased increasing rise rises rose risen rising grew grown"),
            Map.entry("decrease", "decrease decreases decreased decreasing fell fallen falling shrank shrunk"),
            Map.entry("more", "higher highest greater greatest larger largest bigger biggest maximum"),
            Map.entry("less", "lower lowest smaller smallest fewer fewest less least minimum"),
            Map.entry("before", "before prior earlier"),
            Map.entry("after", "after later afterwards"),
            Map.entry("weekday", "weekday weekdays"),
            Map.entry("weekend", "weekend weekends"),
            Map.entry("morning", "morning mornings"),
            Map.entry("afternoon", "afternoon afternoons"),
            Map.entry("evening", "evening evenings"),
            Map.entry("night", "night nights overnight"),
            Map.entry("safe", "safe safely"),
            Map.entry("unsafe", "unsafe dangerous hazardous"),
            Map.entry("true", "true correct"),
            Map.entry("false", "false incorrect"),
            Map.entry("enabled", "enable enables enabled enabling"),
            Map.entry("disabled", "disable disables disabled disabling"),
            Map.entry("allowed", "allow allows allowed permit permits permitted authorized authorised"),
            Map.entry("denied", "deny denies denied forbid forbids forbidden prohibit prohibits prohibited "
                    + "unauthorized unauthorised disallow disallowed"),
            Map.entry("accepted", "accept accepts accepted approve approves approved"),
            Map.entry("rejected", "reject rejects rejected"),
            Map.entry("passed", "passed"),
            Map.entry("failed", "fail fails failed failing"),
            Map.entry("buy", "buy buys buying bought purchase purchases purchased"),
            Map.entry("sell", "sell sells selling sold"),
            Map.entry("ascending", "ascending"),
            Map.entry("descending", "descending"),
            Map.entry("public", "public"),
            Map.entry("private", "private"),
            Map.entry("online", "online"),
            Map.entry("offline", "offline"),
            Map.entry("excluded", "exclude excludes excluded excluding"),
            Map.entry("north", "north northern"),
            Map.entry("south", "south southern"),
            Map.entry("east", "east eastern"),
            Map.entry("west", "west western")));

    private static final Set<String> SENTENCE_INITIAL_STOPWORDS = Set.of(
            "what", "who", "when", "where", "why", "how", "which", "whats",
            "is", "are", "was", "were", "am", "be", "been",
            "do", "does", "did", "can", "could", "would", "should", "will", "shall", "may", "might", "must",
            "has", "have", "had",
            "isn", "aren", "wasn", "weren", "don", "doesn", "didn", "couldn", "wouldn", "shouldn", "won", "hasn",
            "haven", "hadn",
            "the", "a", "an", "this", "that", "these", "those",
            "my", "our", "your", "his", "her", "its", "their", "some", "any", "all", "each", "every",
            "i", "we", "you", "he", "she", "it", "they",
            "there", "here", "let", "if", "and", "but", "or", "so", "also", "now", "then", "yes", "no", "not",
            "in", "on", "at", "for", "from", "to", "with", "by", "of", "about", "after", "before", "during",
            "please", "tell", "explain", "describe", "give", "show", "list", "summarise", "summarize",
            "write", "draft", "compose", "create", "generate", "make", "build", "design", "recommend", "suggest",
            "provide", "help", "find", "translate", "convert", "compare", "define", "calculate", "outline",
            "rewrite", "fix", "review", "check", "name", "identify", "plan", "add", "remove", "use");

    private EntityNumericGuard() {
    }

    public static boolean sameFingerprint(String promptA, String promptB) {
        return fingerprint(promptA).equals(fingerprint(promptB));
    }

    public static Set<String> fingerprint(String prompt) {
        String text = SpelledNumbers.toDigits(prompt.replace('’', '\''));
        String lowerText = text.toLowerCase(Locale.ROOT);
        Set<String> tokens = new LinkedHashSet<>();
        String quantities = DIGIT_GROUPING_COMMA.matcher(extractDates(lowerText, tokens)).replaceAll("");
        addNumbers(quantities, tokens);
        addTimesOfDay(quantities, tokens);
        addCurrencySymbols(quantities, tokens);
        addUnits(quantities, tokens);
        addPolarity(lowerText, tokens);
        addEntities(MERIDIEM.matcher(text).replaceAll(""), tokens);
        return tokens;
    }

    private static String extractDates(String lowerText, Set<String> tokens) {
        Matcher matcher = DATE.matcher(lowerText);
        if (!matcher.find()) {
            return lowerText;
        }
        StringBuilder withoutDates = new StringBuilder(lowerText.length());
        do {
            String withoutNoise = DATE_NOISE.matcher(matcher.group()).replaceAll("");
            tokens.add("date:" + WHITESPACE.matcher(withoutNoise).replaceAll(" "));
            matcher.appendReplacement(withoutDates, " ");
        } while (matcher.find());
        return matcher.appendTail(withoutDates).toString();
    }

    private static void addNumbers(String text, Set<String> tokens) {
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            tokens.add("num:" + matcher.group());
        }
    }

    private static void addTimesOfDay(String text, Set<String> tokens) {
        Matcher matcher = MERIDIEM.matcher(text);
        while (matcher.find()) {
            tokens.add("time:" + matcher.group(1).toLowerCase(Locale.ROOT) + "m");
        }
    }

    private static void addCurrencySymbols(String text, Set<String> tokens) {
        Matcher matcher = CURRENCY_SYMBOL.matcher(text);
        while (matcher.find()) {
            tokens.add("currency:" + CURRENCIES.get(matcher.group(1)));
        }
    }

    private static void addUnits(String lowerText, Set<String> tokens) {
        Matcher matcher = NUMBER_FOLLOWED_BY_WORD.matcher(lowerText);
        while (matcher.find()) {
            String unit = matcher.group(1) == null ? "percent" : UNITS.get(matcher.group(1));
            if (unit != null) {
                tokens.add(unit.startsWith("currency:") ? unit : "unit:" + unit);
            }
        }
    }

    private static void addPolarity(String lowerText, Set<String> tokens) {
        Matcher matcher = WORD.matcher(lowerText);
        while (matcher.find()) {
            String word = matcher.group();
            if (NEGATIONS.contains(word) || word.endsWith("n't")) {
                tokens.add("negated");
            }
            String side = OPPOSITIONS.get(word);
            if (side != null) {
                tokens.add("polarity:" + side);
            }
        }
    }

    private static void addEntities(String text, Set<String> tokens) {
        Matcher matcher = CAPITALIZED_PHRASE.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group();
            boolean atSentenceStart = matcher.start() == 0 || precededBySentenceBoundary(text, matcher.start());
            String entity = atSentenceStart ? withoutLeadingStopwords(candidate) : candidate;
            if (!entity.isEmpty()) {
                tokens.add("entity:" + entity.toLowerCase(Locale.ROOT));
            }
        }
    }

    private static String withoutLeadingStopwords(String phrase) {
        String[] words = WHITESPACE.split(phrase);
        int first = 0;
        while (first < words.length && SENTENCE_INITIAL_STOPWORDS.contains(words[first].toLowerCase(Locale.ROOT))) {
            first++;
        }
        return String.join(" ", Arrays.copyOfRange(words, first, words.length));
    }

    private static boolean precededBySentenceBoundary(String text, int startIndex) {
        int i = startIndex - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
            i--;
        }
        return i < 0 || text.charAt(i) == '.' || text.charAt(i) == '?' || text.charAt(i) == '!';
    }

    private static Map<String, String> lexicon(Map<String, String> surfaceFormsByCanonical) {
        return surfaceFormsByCanonical.entrySet().stream()
                .flatMap(entry -> Stream.of(WHITESPACE.split(entry.getValue()))
                        .map(surface -> Map.entry(surface, entry.getKey())))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> {
                    throw new IllegalStateException("surface form mapped twice: " + a + ", " + b);
                }));
    }
}
