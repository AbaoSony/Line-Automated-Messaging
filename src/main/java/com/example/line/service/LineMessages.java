package com.example.line.service;

import com.linecorp.bot.messaging.model.*;
import java.net.URI;
import java.util.List;

/**
 * SDK factories: construct data without performing network calls.
 * 为 LineMessageService 和业务调用方构造常用 SDK 消息，提前检查可在本地判断的字段约束。
 * 图片可达性、贴图权限及最终平台合法性仍由 LINE 验证，不在工厂中发起网络请求。
 */
public final class LineMessages {
    private LineMessages() {}
    /**
     * 构造普通文本，按 UTF-16 长度限制，避免依赖 LINE 才发现常见参数错误。
     *
     * @param text 非空白文本，最多 5000 个 UTF-16 单元
     * @return 不带 Quick Reply 的 SDK 文本消息
     * @throws IllegalArgumentException 文本缺失、空白或超过长度限制时抛出
     */
    public static TextMessage text(String text) {
        requireText(text, 5000, "Text must contain 1..5000 UTF-16 units");
        return new TextMessage(text);
    }
    /**
     * 构造贴图消息；本地仅检查 ID 的数字形状，不检查平台授权或组合是否存在。
     *
     * @param packageId 非 null 的纯数字贴图包 ID
     * @param stickerId 非 null 的纯数字贴图 ID
     * @return SDK 贴图消息
     * @throws IllegalArgumentException 任一 ID 缺失或不是纯数字时抛出
     */
    public static StickerMessage sticker(String packageId, String stickerId) {
        if (packageId == null || stickerId == null
                || !packageId.matches("[0-9]+") || !stickerId.matches("[0-9]+")) {
            throw new IllegalArgumentException("Sticker packageId/stickerId must be numeric");
        }
        return new StickerMessage(packageId, stickerId);
    }
    /**
     * 构造由 LINE 按公网 URL 获取的图片消息，不在本服务下载或验证图片内容。
     *
     * @param original 原图的绝对 HTTPS URI，必须有主机名且不含 userInfo 凭证
     * @param preview 预览图的绝对 HTTPS URI，必须有主机名且不含 userInfo 凭证
     * @return SDK 图片消息，调用方需保证 LINE 能访问 URL
     * @throws IllegalArgumentException 任一 URI 缺失或不符合本地 HTTPS 约束时抛出
     */
    public static ImageMessage image(URI original, URI preview) {
        requireHttps(original);
        requireHttps(preview);
        return new ImageMessage(original, preview);
    }
    /**
     * 构造简单单气泡 Flex 卡片，标题加粗，正文可换行，并以标题作为无障碍替代文本。
     *
     * @param title 非空白标题，最多 100 个 UTF-16 单元，属于本项目的保守模板限制
     * @param body 非空白正文，最多 1000 个 UTF-16 单元，属于本项目的保守模板限制
     * @return 包含纵向文本区域的 SDK Flex 消息，不包含订单查询等业务动作
     * @throws IllegalArgumentException 标题或正文缺失、空白或超过模板限制时抛出
     */
    public static FlexMessage flexCard(String title, String body) {
        requireText(title, 100, "Flex title must contain 1..100 UTF-16 units");
        requireText(body, 1000, "Flex body must contain 1..1000 UTF-16 units");
        var bubble = new FlexBubble.Builder().body(
                new FlexBox.Builder(FlexBox.Layout.VERTICAL, List.of(
                        new FlexText.Builder().text(title).weight(FlexText.Weight.BOLD).wrap(true).build(),
                        new FlexText.Builder().text(body).wrap(true).build())).build()).build();
        return new FlexMessage(title, bubble);
    }
    /** Quick Reply 的按钮标签和点击后发送文本，限制由 quickReply 工厂统一校验。 */
    public record QuickOption(String label, String text) {}
    /**
     * 为一条提示文本添加 MessageAction 类型的快捷回复选项。
     *
     * @param prompt 非空白提示文本，最多 5000 个 UTF-16 单元
     * @param options 1..13 个非 null 选项；标签非空白且最多 20 单元，点击文本非空白且最多 5000 单元
     * @return 带 Quick Reply 的一条 SDK 文本消息，不是多条根消息
     * @throws IllegalArgumentException 提示、选项数量、标签或点击文本不满足约束时抛出
     */
    public static TextMessage quickReply(String prompt, List<QuickOption> options) {
        text(prompt);
        // 13 是 Quick Reply 的选项上限，与单次发送最多 5 条根消息的限制不同。
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
