package com.example.line.ai.decision;

/** 策略失败只携带安全固定分类，不携带上游信封、凭证或异常正文。 */
public final class TimingFailure extends RuntimeException {
    private final String kind;
    private final boolean upstream;
    /**
     * 区分本地资源/预算拒绝与真实上游故障，取消和旧版本不参与熔断。
     * @param kind 固定分类
     * @param upstream 是否真实上游失败
     */
    public TimingFailure(String kind,boolean upstream) { super(kind);this.kind=kind;this.upstream=upstream; }
    /** @return 安全固定原因 */
    public String kind(){return kind;}
    /** @return 可计入熔断的上游故障 */
    public boolean upstream(){return upstream;}
}
