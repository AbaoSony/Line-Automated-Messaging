package com.example.line.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 显式选择 P2 轮次流程；关闭时保留 P1 单条模式用于回退及兼容验证。 */
@ConfigurationProperties("line.ai.turns")
public record TurnFlowProperties(@DefaultValue("true") boolean enabled) {}
