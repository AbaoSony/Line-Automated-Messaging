package com.example.line.ai.generation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** 只加载负责人提供的原始人设，按台北时间和当前快照前的入站时间替换允许占位符。 */
@Component
public class PersonaLoader {
    private final String template;
    private final Clock clock;
    /** 加载 classpath 人设，缺失或空白启动失败，不自行补写。 */
    public PersonaLoader() { this(read(), Clock.system(ZoneId.of("Asia/Taipei"))); }
    PersonaLoader(String template, Clock clock) {
        if (template == null || template.isBlank()) throw new IllegalArgumentException("Missing or empty persona/default.md");
        this.template = template;
        this.clock = clock;
    }
    /**
     * 替换时间与聊天间隔，不将用户消息拼接进系统提示。
     * @param previous 当前之前的上一条已知私聊用户消息时间
     * @param current 当前 LINE 事件时间
     * @param unavailable 历史读取失败时为 true，不能伪装首次聊天
     * @return 保留原文的人设与确定的时间占位符
     */
    public String render(Instant previous, Instant current, boolean unavailable) {
        var now = clock.instant().atZone(ZoneId.of("Asia/Taipei"));
        String period = now.getHour() < 6 ? "凌晨" : now.getHour() < 12 ? "上午" : now.getHour() < 18 ? "下午" : "晚上";
        String time = now.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE ", Locale.TRADITIONAL_CHINESE))
                + period + " " + now.format(DateTimeFormatter.ofPattern("h:mm"));
        String gap;
        if (unavailable) gap = "上次聊天時間暫不可用";
        else if (previous == null) gap = "這是第一次聊天";
        else {
            long minutes = Math.max(0, Duration.between(previous, current).toMinutes());
            gap = minutes < 1 ? "距離上次聊天不到 1 分鐘" : minutes < 60 ? "距離上次聊天約 " + minutes + " 分鐘"
                    : minutes < 1440 ? "距離上次聊天約 " + minutes / 60 + " 小時" : "距離上次聊天約 " + minutes / 1440 + " 天";
        }
        return template.replace("{{current_time}}", time).replace("{{last_chat_gap}}", gap);
    }
    private static String read() {
        try (var input = new ClassPathResource("persona/default.md").getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Cannot load persona/default.md", failure); }
    }
}
