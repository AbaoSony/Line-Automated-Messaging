package com.example.line.common.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供应用存活检查，位于 HTTP 接入层，不调用 LINE SDK 或发送业务服务。
 * 该检查仅证明应用能响应，不能证明凭证有效、LINE 可达或异步队列空闲。
 */
@RestController
public class HealthController {
    /**
     * 返回进程可响应的健康标志，供本地运行和容器部署后检查。
     *
     * @return 包含 status=UP 的 JSON 对象，不包含配置或凭证
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
