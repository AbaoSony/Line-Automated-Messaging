package com.example.line.ai.coordination;

import com.example.line.ai.AiProperties;
import com.example.line.common.execution.Deadline;
import com.example.line.common.execution.UserWorkTracker;
import com.example.line.common.logging.MdcTaskDecorator;
import java.util.concurrent.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/** 在有界线程池隔离历史查询等待；超时忽略迟到结果，实际执行中的操作仍计入删除等待。 */
@Component
public class DependencyCalls implements DisposableBean,org.springframework.context.SmartLifecycle {
    private final ThreadPoolExecutor executor;
    private final UserWorkTracker tracker;
    private final AiProperties properties;
    /**
     * 使用 AI 同等并发及全局容量限制依赖查询，不能无限创建线程。
     * @param properties 有界资源与依赖预算
     * @param tracker 用户删除协调
     */
    public DependencyCalls(AiProperties properties,UserWorkTracker tracker) {
        this.properties=properties;this.tracker=tracker;
        executor=new ThreadPoolExecutor(properties.workers(),properties.workers(),0,TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()),r -> { var thread=new Thread(r,"line-ai-dependency");thread.setDaemon(true);return thread; });
    }
    /**
     * 等待受入口剩余时间限制的依赖操作，取消不等于 JDBC 已停止。
     * @param deadline 当前阶段截止时间
     * @param action 只允许读取或自行检查阶段截止时间的操作
     * @param <T> 返回类型
     * @return 操作结果
     * @throws IllegalStateException 超时、拒绝、中断或依赖失败时抛出安全摘要
     */
    public <T> T call(Deadline deadline,Callable<T> action) {
        var result=new CompletableFuture<T>();
        Runnable task=new MdcTaskDecorator().decorate(() -> {
            var lease=tracker.register(MDC.get("user_id"));
            if(lease==null) { result.completeExceptionally(new IllegalStateException("User work blocked"));return; }
            try(lease) { if(!deadline.permits(java.time.Duration.ZERO)) throw new IllegalStateException("Dependency deadline");result.complete(action.call()); }
            catch(Exception failure) { result.completeExceptionally(failure); }
        });
        Future<?> pending=executor.submit(task);
        try { return result.get(deadline.limit(properties.dependencyTimeout()).toNanos(),TimeUnit.NANOSECONDS); }
        catch(InterruptedException failure) { pending.cancel(true);Thread.currentThread().interrupt();throw new IllegalStateException("Dependency interrupted"); }
        catch(ExecutionException | TimeoutException failure) { pending.cancel(true);throw new IllegalStateException("Dependency unavailable"); }
    }
    /** 无需独立启动，依赖工作按需运行。 */
    @Override public void start() {}
    /** @return 是否仍允许提交依赖读取 */
    @Override public boolean isRunning(){return !executor.isShutdown();}
    /** @return AI 停止后、业务持久化和日志停止前 */
    @Override public int getPhase(){return com.example.line.common.persistence.DrainingTaskExecutor.PERSISTENCE_PHASE+2;}
    /**
     * 中止依赖等待并限时核实实际读取退出，避免销毁时才产生迟到日志。
     * @throws IllegalStateException 实际读取无法结束或等待中断时抛出
     */
    @Override public void stop() {
        executor.shutdownNow();
        try{if(!executor.awaitTermination(properties.totalTimeout().toMillis(),TimeUnit.MILLISECONDS))
            throw new IllegalStateException("Dependency shutdown did not drain");}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException("Dependency shutdown interrupted");}
    }
    /** Bean 销毁兜底；正常停机已经在日志刷出之前完成。 */
    @Override public void destroy() { if(!executor.isTerminated())stop(); }
}
