package com.example.line.common.logging.recording;

import java.util.Map;
import java.util.UUID;
import java.util.Collections;
import java.util.LinkedHashMap;

/** 保存入队时的独立不可变快照，固定 UUID 使部分批次提交后的重试也不会重复记录。 */
public record RecordedEntry(UUID id, boolean audit, Map<String, Object> data) {
    /**
     * 复制允许空值的记录字段，防止调用线程修改顶层数据。
     * @param id 入队时生成并在重试间复用的主键
     * @param audit 是否为操作审计记录
     * @param data 已过滤并脱离 SDK 对象的 JSON 字段
     */
    public RecordedEntry { data = Collections.unmodifiableMap(new LinkedHashMap<>(data)); }
}
