package com.example.line.common.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linecorp.bot.jackson.ModelObjectMapper;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

/** 保留完整业务数据并按字段排除凭证，不允许日志直接持有原始 Webhook 或 SDK 请求对象。 */
public final class LogData {
    private static final ObjectMapper MAPPER = ModelObjectMapper.createNewObjectMapper();
    private static final Set<String> CREDENTIALS = Set.of("password", "apikey", "secret", "channelsecret",
            "signature", "xlinesignature", "authorization", "headers", "requestheaders", "requestbody");

    private LogData() {}

    /**
     * 复制消息或业务元数据，递归移除 Token、签名、密钥和原始报文容器；文本内容保持完整。
     * @param value 仅包含业务字段的消息、列表或映射，不传入完整 Webhook 请求
     * @return JSON 可直接输出的独立业务数据；null 输入返回 null
     * @throws IllegalArgumentException 无法转换为 JSON 的业务对象时抛出
     */
    public static Object business(Object value) {
        if (value == null) return null;
        JsonNode copy = MAPPER.valueToTree(value);
        prune(copy);
        return MAPPER.convertValue(copy, Object.class);
    }

    private static void prune(JsonNode node) {
        if (node instanceof ObjectNode object) {
            var names = new ArrayList<String>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                String normalized = name.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
                if (CREDENTIALS.contains(normalized) || normalized.endsWith("token") || normalized.endsWith("password")
                        || normalized.endsWith("apikey") || normalized.endsWith("secret") || normalized.endsWith("signature")) {
                    object.remove(name);
                } else prune(object.get(name));
            }
        } else if (node.isArray()) node.forEach(LogData::prune);
    }
}
