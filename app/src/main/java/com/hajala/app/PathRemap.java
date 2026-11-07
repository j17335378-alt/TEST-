package com.hajala.app;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure helper: re-points absolute MangaVerse image paths stored inside saved JSON at this install's media folder. */
final class PathRemap {
    // matches file:///data/user/0/<pkg>/files/media/  and the JSON-escaped form file:\/\/\/data\/...\/files\/media\/
    private static final Pattern P = Pattern.compile("file:(?:\\\\?/){2}[^\"\\s]*?files(?:\\\\?/)media(?:\\\\?/)");

    private PathRemap() {}

    /** [mediaPrefix] is e.g. "file:///data/user/0/com.hajala.app/files/media/". */
    static String remap(String s, String mediaPrefix) {
        if (s == null || !s.contains("media")) return s;
        Matcher m = P.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String rep = m.group().contains("\\") ? mediaPrefix.replace("/", "\\/") : mediaPrefix;
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
