package com.deepseek.demo.util;

public final class StringUtils {

    private StringUtils() {}

    public static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
