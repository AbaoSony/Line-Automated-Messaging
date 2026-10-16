package com.example.line.common.execution;

/** 将同步 SDK 建立调用时的用户租约传给传输监听器，不携带任何请求内容或凭证。 */
public final class LineSendScope implements AutoCloseable {
    private static final ThreadLocal<UserWorkTracker.Lease> CURRENT = new ThreadLocal<>();
    private final UserWorkTracker.Lease previous;
    private boolean closed;
    private LineSendScope(UserWorkTracker.Lease lease) {
        previous=CURRENT.get();CURRENT.set(lease);
    }
    /**
     * 只在当前发送调用栈内绑定已登记的工作，不创建第二份用户状态。
     * @param lease 已取得的发送租约
     * @return 用于恢复上层绑定的作用域
     */
    public static LineSendScope open(UserWorkTracker.Lease lease) { return new LineSendScope(lease); }
    /**
     * 在 SDK 建立实际调用时保留子租约，取消 Future 后由传输终止回调释放。
     * @return 子租约，无业务发送作用域的独立调用返回空
     * @throws IllegalStateException 上层租约已结束时抛出
     */
    public static UserWorkTracker.Lease retainTransport() {
        var lease=CURRENT.get();return lease==null?null:lease.retain();
    }
    /** 恢复调用线程绑定，不能把租约残留给复用线程。 */
    @Override public void close() {
        if(closed)return;closed=true;
        if(previous==null)CURRENT.remove();else CURRENT.set(previous);
    }
}
