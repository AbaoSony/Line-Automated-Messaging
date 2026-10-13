package com.example.line.service;

import com.linecorp.bot.messaging.model.*;
import java.net.URI;
import java.util.List;

/** SDK factories: construct data without performing network calls. */
public final class LineMessages {
    private LineMessages() {}
    public static TextMessage text(String text) {
        requireText(text, 5000, "Text must contain 1..5000 UTF-16 units");
        return new TextMessage(text);
    }
    public static StickerMessage sticker(String packageId, String stickerId) {
        if (packageId == null || stickerId == null
                || !packageId.matches("[0-9]+") || !stickerId.matches("[0-9]+")) {
            throw new IllegalArgumentException("Sticker packageId/stickerId must be numeric");
        }
        return new StickerMessage(packageId, stickerId);
    }
    public static ImageMessage image(URI original, URI preview) {
        requireHttps(original);
        requireHttps(preview);
        return new ImageMessage(original, preview);
    }
    public static FlexMessage flexCard(String title, String body) {
        requireText(title, 100, "Flex title must contain 1..100 UTF-16 units");
        requireText(body, 1000, "Flex body must contain 1..1000 UTF-16 units");
        var bubble = new FlexBubble.Builder().body(
                new FlexBox.Builder(FlexBox.Layout.VERTICAL, List.of(
                        new FlexText.Builder().text(title).weight(FlexText.Weight.BOLD).wrap(true).build(),
                        new FlexText.Builder().text(body).wrap(true).build())).build()).build();
        return new FlexMessage(title, bubble);
    }
    public record QuickOption(String label, String text) {}
    public static TextMessage quickReply(String prompt, List<QuickOption> options) {
        text(prompt);
        if (options == null || options.isEmpty() || options.size() > 13
                || options.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Quick Reply requires 1..13 options");
        }
        var items = options.stream().map(option -> {
            requireText(option.label(), 20, "Quick Reply label must contain 1..20 UTF-16 units");
            text(option.text());
            return new QuickReplyItem(new MessageAction.Builder()
                    .label(option.label()).text(option.text()).build());
        }).toList();
        return new TextMessage.Builder(prompt).quickReply(new QuickReply(items)).build();
    }
    private static void requireHttps(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Image URL must be an absolute HTTPS URL without credentials");
        }
    }
    private static void requireText(String value, int max, String error) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException(error);
    }
}
