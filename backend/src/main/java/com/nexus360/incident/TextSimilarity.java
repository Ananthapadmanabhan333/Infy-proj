package com.nexus360.incident;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic text similarity for duplicate detection: token Jaccard over normalised words (stop words
 * removed, light stemming, IT synonyms folded) blended with character-trigram Dice on titles to tolerate typos.
 */
public final class TextSimilarity {

    private static final Set<String> STOP_WORDS = Set.of("a", "an", "the", "is", "are", "was", "were", "be", "been", "to", "of",
            "in", "on", "at", "for", "and", "or", "but", "with", "my", "our", "we", "i", "me", "it", "its", "this", "that", "from",
            "by", "as", "since", "today", "now", "please", "help", "issue", "problem", "getting", "get", "got", "when", "while",
            "again", "still", "any", "all", "has", "have", "had", "do", "does", "did", "am", "so", "very", "just", "also", "there",
            "not", "no", "keeps", "keep", "can", "will", "would", "could", "should", "us", "you", "your", "they", "them", "some");

    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("cannot", "unable"), Map.entry("cant", "unable"), Map.entry("can't", "unable"), Map.entry("unable", "unable"),
            Map.entry("inaccessible", "unavailable"), Map.entry("down", "unavailable"), Map.entry("outage", "unavailable"),
            Map.entry("unavailable", "unavailable"), Map.entry("unreachable", "unavailable"), Map.entry("offline", "unavailable"),
            Map.entry("accessible", "access"), Map.entry("accessing", "access"), Map.entry("access", "access"),
            Map.entry("open", "access"), Map.entry("opening", "access"), Map.entry("reach", "access"),
            Map.entry("login", "login"), Map.entry("logon", "login"), Map.entry("signin", "login"), Map.entry("sign", "login"),
            Map.entry("log", "login"), Map.entry("loading", "load"), Map.entry("loads", "load"), Map.entry("load", "load"),
            Map.entry("spinning", "load"), Map.entry("blank", "load"),
            Map.entry("error", "fail"), Map.entry("errors", "fail"), Map.entry("failure", "fail"), Map.entry("failing", "fail"),
            Map.entry("failed", "fail"), Map.entry("fails", "fail"), Map.entry("crash", "fail"), Map.entry("crashing", "fail"),
            Map.entry("broken", "fail"), Map.entry("503", "fail"), Map.entry("500", "fail"), Map.entry("502", "fail"),
            Map.entry("slow", "slow"), Map.entry("latency", "slow"), Map.entry("lag", "slow"), Map.entry("sluggish", "slow"),
            Map.entry("portal", "app"), Map.entry("site", "app"), Map.entry("website", "app"), Map.entry("application", "app"),
            Map.entry("app", "app"), Map.entry("system", "app"), Map.entry("page", "app"), Map.entry("webpage", "app"),
            Map.entry("salary", "payroll"), Map.entry("payslip", "payroll"), Map.entry("payslips", "payroll"),
            Map.entry("e-mail", "email"), Map.entry("mail", "email"), Map.entry("outlook", "email"), Map.entry("mailbox", "email"));

    private TextSimilarity() {}

    public static Set<String> tokens(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null) {
            return result;
        }
        String normalized = text.toLowerCase(Locale.ROOT).replace("can't", "cant").replaceAll("[^a-z0-9]+", " ");
        for (String raw : normalized.split(" ")) {
            if (raw.length() < 2 || STOP_WORDS.contains(raw)) {
                continue;
            }
            String token = SYNONYMS.getOrDefault(raw, null);
            if (token == null) {
                String stemmed = stem(raw);
                token = SYNONYMS.getOrDefault(stemmed, stemmed);
            }
            if (!STOP_WORDS.contains(token)) {
                result.add(token);
            }
        }
        return result;
    }

    public static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    public static double trigramDice(String a, String b) {
        Set<String> ta = trigrams(a);
        Set<String> tb = trigrams(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(ta);
        intersection.retainAll(tb);
        return 2.0 * intersection.size() / (ta.size() + tb.size());
    }

    /** Text-only similarity in [0, 1]. */
    public static double score(String titleA, String descriptionA, String titleB, String descriptionB) {
        double tokenScore = jaccard(tokens(join(titleA, descriptionA)), tokens(join(titleB, descriptionB)));
        double titleTokens = jaccard(tokens(titleA), tokens(titleB));
        double titleTrigrams = trigramDice(titleA, titleB);
        return clamp(0.45 * tokenScore + 0.35 * titleTokens + 0.20 * titleTrigrams);
    }

    private static Set<String> trigrams(String text) {
        Set<String> grams = new HashSet<>();
        if (text == null) {
            return grams;
        }
        String s = "  " + text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim() + " ";
        for (int i = 0; i + 3 <= s.length(); i++) {
            grams.add(s.substring(i, i + 3));
        }
        return grams;
    }

    private static String stem(String word) {
        if (word.length() > 5 && word.endsWith("ing")) {
            return word.substring(0, word.length() - 3);
        }
        if (word.length() > 4 && word.endsWith("ed")) {
            return word.substring(0, word.length() - 2);
        }
        if (word.length() > 4 && word.endsWith("es")) {
            return word.substring(0, word.length() - 2);
        }
        if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    private static String join(String a, String b) {
        return (a == null ? "" : a) + " " + (b == null ? "" : b);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
