package com.myname.expensetracker.purchases;

import java.util.Locale;

/**
 * Normalizes Arabic/English message text for matching while remembering where each character came from,
 * so extracted values (like the merchant name) can be cut from the original, un-normalized text.
 *
 * Folding: lower-case, Arabic-Indic/Persian digits to 0-9, Arabic decimal/thousands separators to . and ,
 * alef forms to ا, ى to ي, ة to ه, and removal of tatweel, diacritics and invisible direction marks.
 */
final class TextFold {
    final String original;
    final String folded;
    private final int[] map; // folded index -> original index

    private TextFold(String original, String folded, int[] map) {
        this.original = original;
        this.folded = folded;
        this.map = map;
    }

    static TextFold of(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        int[] map = new int[text.length() + 1];
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = foldChar(text.charAt(i), true);
            if (c == 0) continue;
            sb.append(c);
            map[n++] = i;
        }
        map[n] = text.length();
        return new TextFold(text, sb.toString(), map);
    }

    /** The original text behind folded[start, end). */
    String originalRange(int start, int end) {
        if (end <= start) return "";
        return original.substring(map[start], map[end - 1] + 1);
    }

    /** Arabic-only folding (no lower-casing), safe to apply to regular expressions from the rules file. */
    static String foldArabic(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = foldChar(s.charAt(i), false);
            if (c != 0) sb.append(c);
        }
        return sb.toString();
    }

    static String fold(String s) {
        return of(s).folded;
    }

    /** Returns the folded char, or 0 to drop it. */
    private static char foldChar(char c, boolean lower) {
        if (c >= '٠' && c <= '٩') return (char) ('0' + (c - '٠')); // Arabic-Indic digits
        if (c >= '۰' && c <= '۹') return (char) ('0' + (c - '۰')); // Persian digits
        switch (c) {
            case '٫': return '.';               // Arabic decimal separator
            case '٬': return ',';               // Arabic thousands separator
            case '،': return lower ? ',' : c;   // Arabic comma
            case 'أ': case 'إ': case 'آ': case 'ٱ': return 'ا'; // alef forms
            case 'ى': return 'ي';          // alef maqsura -> ya
            case 'ة': return 'ه';          // ta marbuta -> ha
            case 'ـ': return 0;                 // tatweel
            case ' ': case ' ': case ' ': return ' ';
            case '\r': return lower ? '\n' : c;
            default: break;
        }
        if (c >= 'ً' && c <= 'ْ') return 0; // harakat
        if (c == '‎' || c == '‏' || c == '؜' || c == '​' || c == '﻿'
                || (c >= '‪' && c <= '‮') || (c >= '⁦' && c <= '⁩')) return 0; // direction marks
        if (lower) {
            String l = String.valueOf(c).toLowerCase(Locale.ROOT);
            if (l.length() == 1) return l.charAt(0);
        }
        return c;
    }
}
