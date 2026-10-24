package com.adongcraft.gonggao;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

record Notice(String id, String key, String source, String title, String summary, List<String> pages, int order) {
    String readKey() {
        try {
            String content = title + "\0" + summary + "\0" + String.join("\0", pages);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return id + "@" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
