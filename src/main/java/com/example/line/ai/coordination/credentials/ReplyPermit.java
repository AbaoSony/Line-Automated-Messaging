package com.example.line.ai.coordination.credentials;

import com.example.line.common.execution.Deadline;

/** 发送权取得后短暂持有凭证；禁止序列化或记录，只在当前内存发送作用域使用。 */
public final class ReplyPermit implements AutoCloseable {
    private String token;
    private final Deadline deadline;
    /**
     * 绑定已取得发送权的凭证与原始总截止。
     * @param token 当前轮次最后一条有效成员的凭证
     * @param deadline 不可延后的总截止
     */
    public ReplyPermit(String token, Deadline deadline) { this.token = token; this.deadline = deadline; }
    /** @return 仅供一次 LINE 调用使用的凭证，关闭后为空 */
    public String token() { return token; }
    /** @return 原始总截止，不能从取得许可时重新计时 */
    public Deadline deadline() { return deadline; }
    /** 清除发送作用域的凭证引用。 */
    @Override public void close() { token = null; }
    /** @return 不含凭证的固定对象说明 */
    @Override public String toString() { return "ReplyPermit"; }
}
