package com.adongcraft.gonggao;

import java.util.ArrayList;
import java.util.List;

/** Conservative widths for vanilla book text. Header and navigation keep their own lines. */
final class BookLayout {
    static final int WIDTH = 108;
    static final int BODY_LINES = 9;
    private BookLayout() { }
    static List<String> paginate(List<String> paragraphs) {
        List<String> pages = new ArrayList<>();
        for (String paragraph : paragraphs) {
            List<String> lines = wrap(paragraph);
            for (int start = 0; start < lines.size(); start += BODY_LINES)
                pages.add(String.join("\n", lines.subList(start, Math.min(start + BODY_LINES, lines.size()))));
        }
        return List.copyOf(pages);
    }
    static List<String> wrap(String text) {
        List<String> lines = new ArrayList<>();
        for (String explicit : text.replace("\r", "").split("\n", -1)) {
            StringBuilder line = new StringBuilder(); int width = 0;
            for (int cp : explicit.codePoints().toArray()) {
                int advance = width(cp);
                if (width + advance > WIDTH && !line.isEmpty()) { lines.add(line.toString()); line.setLength(0); width = 0; }
                line.appendCodePoint(cp); width += advance;
            }
            lines.add(line.toString().stripTrailing());
        }
        return lines;
    }
    static String shorten(String text, int maxWidth) {
        StringBuilder out = new StringBuilder(); int used = 0;
        for (int cp : text.codePoints().toArray()) {
            int advance = width(cp) + 1;
            if (used + advance > maxWidth) return out + "…";
            out.appendCodePoint(cp); used += advance;
        }
        return out.toString();
    }
    private static int width(int cp) {
        if (cp == ' ') return 4;
        if (cp < 128) return "il.,:;!'|".indexOf(cp) >= 0 ? 3 : 6;
        return 9;
    }
}
