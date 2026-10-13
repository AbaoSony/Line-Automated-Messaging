package com.example.line.service;

import com.example.line.config.WebhookProperties;
import com.linecorp.bot.messaging.client.MessagingApiBlobClient;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * 通过 MessagingApiBlobClient 将 LINE 图片/文件内容流式写入调用方输出流。
 * 与 WebhookProperties 协作限制下载字节数；当前 Handler 不自动调用，存储路径与清理由业务方负责。
 */
@Service
public class LineContentService {
    private final MessagingApiBlobClient client;
    private final long maxBytes;

    /**
     * 注入内容客户端和下载资源边界。
     *
     * @param client 由 LineClientConfig 创建、使用 Access Token 鉴权且关闭报文日志的 SDK 客户端
     * @param properties 有效资源配置，默认单次内容上限为 10 MiB
     */
    public LineContentService(MessagingApiBlobClient client, WebhookProperties properties) {
        this.client = client;
        this.maxBytes = properties.contentMaxBytes();
    }

    /**
     * Call from a worker for image/file message IDs. Caller owns the destination stream.
     * 按真实 messageId 获取 LINE 托管内容，流式复制并始终关闭上游；不关闭调用方输出流。
     *
     * @param messageId 非空白的入站图片/文件 messageId，不是 webhookEventId；外部内容 URL 不由此方法抓取
     * @param destination 非 null、已打开的目标流，调用方负责关闭以及失败时清理部分文件
     * @return 成功写入目标流的总字节数，包含零字节内容的情况
     * @throws IllegalArgumentException messageId 缺失或为空白时抛出
     * @throws IOException 超过配置字节上限，或读写流失败时抛出
     * @throws java.util.concurrent.TimeoutException 获取 SDK 内容结果超过 20 秒时抛出
     * @throws InterruptedException 等待 SDK 结果时线程被中断则抛出
     * @throws java.util.concurrent.ExecutionException SDK Future 异常完成，例如网络或 LINE API 拒绝时抛出
     * @throws Exception 遵循下载方法的上层异常传播签名，调用方应做资源和部分文件清理
     */
    public long downloadTo(String messageId, OutputStream destination) throws Exception {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("Missing messageId");
        }
        // 20 秒只限制获取内容响应的等待，不是整个流式复制的总截止时间。
        // 本方法超时后未主动取消 SDK 请求，调用方不能把等待结束等同于远端传输已中止。
        var content = client.getMessageContent(messageId).get(20, TimeUnit.SECONDS).body();
        try (var input = content.byteStream()) {
            // 8 KiB 是本地复制缓冲区而非 LINE 限制，避免一次把整个文件读进堆内存。
            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maxBytes) {
                    throw new IOException("LINE content exceeds configured byte limit");
                }
                destination.write(buffer, 0, count);
            }
            return total;
        }
    }
}
