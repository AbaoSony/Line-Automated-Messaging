package com.example.line.ai.coordination;

import com.example.line.ai.AiProperties;
import com.example.line.ai.aggregation.AggregationProperties;
import com.example.line.ai.decision.DecisionProperties;
import com.example.line.common.execution.Deadline;
import com.example.line.common.logging.MdcTaskDecorator;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** 单实例有界用户 FIFO 调度；同用户等待任务不占用工作线程，不以 Redis 抢锁顺序冒充接收顺序。 */
@Component
public class SessionScheduler implements SmartLifecycle, com.example.line.user.UserDataPurger {
    private final AiProperties properties;
    private final ExecutorService workers;
    private final ScheduledExecutorService timer;
    private final ThreadPoolExecutor hints;
    private final MeterRegistry meters;
    private final Map<String,Lane> lanes=new HashMap<>();
    private final ArrayDeque<String> ready=new ArrayDeque<>();
    private int running,queued;
    private boolean accepting=true;
    private volatile TurnCoordinator turns;
    private final ThreadLocal<Task> activeTask=new ThreadLocal<>();

    /**
     * 创建有界会话调度与独立过期计时器，空闲用户状态随队列结束清理。
     * @param properties 并发、排队和等待上限
     * @param meters 队列水位指标
     */
    public SessionScheduler(AiProperties properties,MeterRegistry meters) {
        this.properties=properties;this.meters=meters;
        workers=Executors.newFixedThreadPool(properties.workers(),r -> {var thread=new Thread(r,"line-ai-session");thread.setDaemon(true);return thread;});
        var scheduled=new ScheduledThreadPoolExecutor(1,r -> {var thread=new Thread(r,"line-ai-expiry");thread.setDaemon(true);return thread;});
        // 轮次频繁替换唤醒时必须释放旧定时引用，不能把已取消任务保留到原始到期。
        scheduled.setRemoveOnCancelPolicy(true);timer=scheduled;
        hints=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(properties.queueCapacity()),
                r -> {var thread=new Thread(r,"line-ai-hint");thread.setDaemon(true);return thread;});
        meters.gauge("ai.queue.size",this,scheduler -> scheduler.turns==null?scheduler.queued:scheduler.turns.watermark().queuedJobs());
    }
    /**
     * 在空闲阶段一次性切换为轮次调度，后续逐条入口禁止使用，生产由 AggregatedChatFlow 在启动时切换。
     * @param aggregation 聚合资源与预留
     * @param decision 判断额度
     * @param generationLimit 正文共享阶段上限
     * @param work 后续策略/正文执行适配，不能在返回后继续持有快照或调用资源
     * @return 唯一轮次状态所有者，复用现有线程与删除/停机入口
     * @throws IllegalStateException 存在逐条工作、已经切换或正在停机时抛出
     * @throws IllegalArgumentException 共享资源配置无效时抛出
     */
    public synchronized TurnCoordinator enableTurns(AggregationProperties aggregation,DecisionProperties decision,
                                                     java.time.Duration generationLimit,TurnWork work) {
        return enableTurns(aggregation,decision,generationLimit,work,System::nanoTime,TurnTimer.using(timer,System::nanoTime));
    }
    synchronized TurnCoordinator enableTurns(AggregationProperties aggregation,DecisionProperties decision,
                                              java.time.Duration generationLimit,TurnWork work,java.util.function.LongSupplier clock,TurnTimer turnTimer) {
        if(!accepting || turns!=null || !isIdle() || !lanes.isEmpty())throw new IllegalStateException("Session scheduler is not idle for mode switch");
        turns=new TurnCoordinator(properties,aggregation,decision,generationLimit,workers,turnTimer,clock,work);
        return turns;
    }
    /**
     * 按有序入口调用顺序加入用户队列，返回业务完成阶段供 Webhook 保持幂等占用。
     * @param user 完整用户
     * @param deadline 原始截止时间
     * @param action 当前短时处理，凭证不出此内存生命周期
     * @param rejected 拒绝/超时提示，必须自行检查发送预算
     * @param cancelled 删除或关闭时释放工作计数，不发送旧任务
     * @return 终态完成阶段
     */
    public synchronized CompletionStage<Void> submit(String user,Deadline deadline,Runnable action,Runnable rejected,Runnable cancelled) {
        if(turns!=null)throw new IllegalStateException("Legacy submission is disabled in turn mode");
        var completion=new CompletableFuture<Void>();
        var decorator=new MdcTaskDecorator();
        if(!accepting || queued>=properties.queueCapacity() || !lanes.containsKey(user) && lanes.size()>=properties.maxUsers()
                || lanes.containsKey(user) && lanes.get(user).tasks.size()>=properties.userQueueCapacity()) {
            runRejected(decorator.decorate(rejected),decorator.decorate(cancelled),completion);
            return completion;
        }
        var lane=lanes.computeIfAbsent(user,ignored -> new Lane());
        var task=new Task(decorator.decorate(action),decorator.decorate(rejected),decorator.decorate(cancelled),completion);
        lane.tasks.addLast(task);queued++;
        if(!lane.active && !ready.contains(user)) ready.addLast(user);
        task.expiry=timer.schedule(() -> expire(user,task),Math.max(1,deadline.limit(properties.queueWait()).toMillis()),TimeUnit.MILLISECONDS);
        dispatch();
        return completion;
    }
    /**
     * 删除或有效取消关注使该用户未发送任务失效；逐条工作中断后仍等待实际退出并拒绝迟到发送。
     * @param user 完整用户
     */
    public void cancel(String user) {
        TurnCoordinator current;
        synchronized(this) {current=turns;if(current==null)cancelLegacy(user);}
        if(current!=null)current.cancel(user);
    }
    private void cancelLegacy(String user) {
        Lane lane=lanes.get(user);
        if(lane==null) return;
        if(lane.current!=null){lane.current.stopped=true;if(lane.current.thread!=null)lane.current.thread.interrupt();}
        for(var task:lane.tasks) { task.expiry.cancel(false);task.cancelled.run();task.result.complete(null);queued--; }
        lane.tasks.clear();ready.remove(user);
        if(!lane.active) lanes.remove(user);
    }
    /**
     * 接入统一手动删除，轮次状态须先由删除守卫取消并在事务外排空。
     * @param userId 完整用户标识
     * @throws IllegalArgumentException 用户标识为空时抛出
     * @throws IllegalStateException 仍有轮次或实际调用时抛出，不能在事务内等待或假报已删除
     */
    @Override public void purge(String userId) {
        if(userId==null || userId.isBlank())throw new IllegalArgumentException("Missing userId");
        TurnCoordinator current=turns;
        if(current!=null)current.requireDrained(userId);
    }
    private synchronized void expire(String user,Task task) {
        Lane lane=lanes.get(user);
        if(lane==null || !lane.tasks.remove(task)) return;
        queued--;runRejected(task.rejected,task.cancelled,task.result);
        if(lane.tasks.isEmpty() && !lane.active) {lanes.remove(user);ready.remove(user);}
    }
    /**
     * 将拒绝提示交给已有有界专用执行器，静默和失败均结束去重占用。
     * @param action 自行检查静默、冷却和原始截止的提示
     * @param cleanup 实际提示退出或队列拒绝后释放成员工作登记
     * @return 提示处理结束的正常完成阶段，不能驱动 Webhook 重新消费
     */
    public CompletionStage<Void> rejectHint(Runnable action,Runnable cleanup) {
        var result=new CompletableFuture<Void>();
        var decorator=new MdcTaskDecorator();
        runRejected(decorator.decorate(action),decorator.decorate(cleanup),result);
        return result.handle((ignored,failure) -> null);
    }
    private void runRejected(Runnable rejected,Runnable cancelled,CompletableFuture<Void> result) {
        // 提示不在 HTTP/排序线程执行；计时器只负责入队，饱和时明确取消而不回退调用者。
        try { hints.execute(() -> {try {rejected.run();result.complete(null);} catch(RuntimeException failure){result.completeExceptionally(failure);} finally{cancelled.run();}}); }
        catch(RejectedExecutionException failure) {
            meters.counter("ai.hint.rejected").increment();
            com.example.line.common.logging.BusinessLog.write(org.slf4j.LoggerFactory.getLogger(getClass()),org.slf4j.event.Level.WARN,
                    com.example.line.common.logging.LogEvents.AI_QUEUE_REJECTED,"Busy hint queue rejected",
                    com.example.line.common.logging.BusinessLog.fields("stage","hint","outcome","SKIPPED"));
            cancelled.run();result.completeExceptionally(failure);
        }
    }
    private void dispatch() {
        while(running<properties.workers() && !ready.isEmpty()) {
            String user=ready.removeFirst();Lane lane=lanes.get(user);
            if(lane==null || lane.active || lane.tasks.isEmpty()) continue;
            var task=lane.tasks.removeFirst();task.expiry.cancel(false);lane.active=true;lane.current=task;running++;queued--;
            workers.execute(() -> {
                synchronized(this){task.thread=Thread.currentThread();if(task.stopped)task.thread.interrupt();}
                activeTask.set(task);
                try {if(!task.stopped)task.action.run();task.result.complete(null);} catch(RuntimeException failure){task.result.completeExceptionally(failure);}
                finally {activeTask.remove();task.cancelled.run();finished(user);}
            });
        }
    }
    private synchronized void finished(String user) {
        running--;Lane lane=lanes.get(user);lane.active=false;lane.current=null;
        if(lane.tasks.isEmpty()) lanes.remove(user);else ready.addLast(user);
        dispatch();notifyAll();
    }
    /** 无需额外启动，构造时仅创建线程池，提交时才运行任务。 */
    @Override public void start() {}
    /** @return 是否仍接收当前会话 */
    @Override public synchronized boolean isRunning() {return accepting;}
    /** @return 位于 Webhook 排空之后、业务落库排空之前的生命周期阶段 */
    @Override public int getPhase() {return com.example.line.common.persistence.DrainingTaskExecutor.PERSISTENCE_PHASE+3;}
    /**
     * 优雅停机停止接收，取消未发送任务；SENDING 与提示共享一个有限收尾预算。
     * @throws IllegalStateException 未实际排空或等待中断时抛出
     */
    @Override public void stop() {
        TurnCoordinator current;
        synchronized(this) {accepting=false;current=turns;for(String user:List.copyOf(lanes.keySet())) cancelLegacy(user);}
        if(current!=null)current.stop();
        timer.shutdownNow();workers.shutdown();hints.shutdown();
        long end=System.nanoTime()+properties.totalTimeout().toNanos();
        try {
            boolean ended=workers.awaitTermination(Math.max(0,end-System.nanoTime()),TimeUnit.NANOSECONDS);
            ended=hints.awaitTermination(Math.max(0,end-System.nanoTime()),TimeUnit.NANOSECONDS) && ended;
            if(!ended){workers.shutdownNow();hints.shutdownNow();
                com.example.line.common.logging.BusinessLog.write(org.slf4j.LoggerFactory.getLogger(getClass()),org.slf4j.event.Level.ERROR,
                        com.example.line.common.logging.LogEvents.EXECUTOR_REJECTED,"AI shutdown did not drain",
                        com.example.line.common.logging.BusinessLog.fields("reason","shutdown_not_drained","outcome","FAILED"));
                throw new IllegalStateException("AI shutdown did not drain");}
        }
        catch(InterruptedException failure){workers.shutdownNow();hints.shutdownNow();Thread.currentThread().interrupt();throw new IllegalStateException("AI shutdown interrupted");}
    }
    /** @return 会话及拒绝提示均已排空时返回 true */
    public synchronized boolean isIdle() {return running==0 && queued==0 && hints.getActiveCount()==0 && hints.getQueue().isEmpty() && (turns==null || turns.isIdle());}
    /** @return 逐条兼容模式的当前任务已取消时为 true，迟到模型不能在重新关注后发送 */
    public boolean currentTaskCancelled(){var task=activeTask.get();return task!=null && task.stopped;}
    private static final class Lane {boolean active;Task current;final ArrayDeque<Task> tasks=new ArrayDeque<>();}
    private static final class Task {
        final Runnable action,rejected,cancelled;final CompletableFuture<Void> result;ScheduledFuture<?> expiry;
        volatile boolean stopped;Thread thread;
        Task(Runnable action,Runnable rejected,Runnable cancelled,CompletableFuture<Void> result){this.action=action;this.rejected=rejected;this.cancelled=cancelled;this.result=result;}
    }
}
