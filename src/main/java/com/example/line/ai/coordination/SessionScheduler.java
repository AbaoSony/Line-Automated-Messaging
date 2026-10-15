package com.example.line.ai.coordination;

import com.example.line.ai.AiProperties;
import com.example.line.common.execution.Deadline;
import com.example.line.common.logging.MdcTaskDecorator;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** 单实例有界用户 FIFO 调度；同用户等待任务不占用工作线程，不以 Redis 抢锁顺序冒充接收顺序。 */
@Component
public class SessionScheduler implements SmartLifecycle {
    private final AiProperties properties;
    private final ExecutorService workers;
    private final ScheduledExecutorService timer;
    private final ThreadPoolExecutor hints;
    private final MeterRegistry meters;
    private final Map<String,Lane> lanes=new HashMap<>();
    private final ArrayDeque<String> ready=new ArrayDeque<>();
    private int running,queued;
    private boolean accepting=true;

    /**
     * 创建有界会话调度与独立过期计时器，空闲用户状态随队列结束清理。
     * @param properties 并发、排队和等待上限
     * @param meters 队列水位指标
     */
    public SessionScheduler(AiProperties properties,MeterRegistry meters) {
        this.properties=properties;this.meters=meters;
        workers=Executors.newFixedThreadPool(properties.workers(),r -> {var thread=new Thread(r,"line-ai-session");thread.setDaemon(true);return thread;});
        timer=Executors.newSingleThreadScheduledExecutor(r -> {var thread=new Thread(r,"line-ai-expiry");thread.setDaemon(true);return thread;});
        hints=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(properties.queueCapacity()),
                r -> {var thread=new Thread(r,"line-ai-hint");thread.setDaemon(true);return thread;});
        meters.gauge("ai.queue.size",this,scheduler -> scheduler.queued);
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
     * 删除屏障取消该用户全部等待任务；正在执行的任务由工作跟踪器等待或在发送前中止。
     * @param user 完整用户
     */
    public synchronized void cancel(String user) {
        Lane lane=lanes.get(user);
        if(lane==null) return;
        for(var task:lane.tasks) { task.expiry.cancel(false);task.cancelled.run();task.result.complete(null);queued--; }
        lane.tasks.clear();ready.remove(user);
        if(!lane.active) lanes.remove(user);
    }
    private synchronized void expire(String user,Task task) {
        Lane lane=lanes.get(user);
        if(lane==null || !lane.tasks.remove(task)) return;
        queued--;runRejected(task.rejected,task.cancelled,task.result);
        if(lane.tasks.isEmpty() && !lane.active) {lanes.remove(user);ready.remove(user);}
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
            var task=lane.tasks.removeFirst();task.expiry.cancel(false);lane.active=true;running++;queued--;
            workers.execute(() -> {
                try {task.action.run();task.result.complete(null);} catch(RuntimeException failure){task.result.completeExceptionally(failure);}
                finally {task.cancelled.run();finished(user);}
            });
        }
    }
    private synchronized void finished(String user) {
        running--;Lane lane=lanes.get(user);lane.active=false;
        if(lane.tasks.isEmpty()) lanes.remove(user);else ready.addLast(user);
        dispatch();notifyAll();
    }
    /** 无需额外启动，构造时仅创建线程池，提交时才运行任务。 */
    @Override public void start() {}
    /** @return 是否仍接收当前会话 */
    @Override public synchronized boolean isRunning() {return accepting;}
    /** @return 位于 Webhook 排空之后、业务落库排空之前的生命周期阶段 */
    @Override public int getPhase() {return com.example.line.common.persistence.DrainingTaskExecutor.PERSISTENCE_PHASE+2;}
    /** 优雅停机停止接收，取消等待任务并等待当前短时处理结束。 */
    @Override public void stop() {
        synchronized(this) {accepting=false;for(String user:List.copyOf(lanes.keySet())) cancel(user);}
        timer.shutdownNow();workers.shutdown();hints.shutdown();
        try {
            if(!workers.awaitTermination(properties.totalTimeout().plusSeconds(1).toMillis(),TimeUnit.MILLISECONDS)) workers.shutdownNow();
            if(!hints.awaitTermination(properties.totalTimeout().plusSeconds(1).toMillis(),TimeUnit.MILLISECONDS)) hints.shutdownNow();
        }
        catch(InterruptedException failure){workers.shutdownNow();hints.shutdownNow();Thread.currentThread().interrupt();}
    }
    /** @return 会话及拒绝提示均已排空时返回 true */
    public synchronized boolean isIdle() {return running==0 && queued==0 && hints.getActiveCount()==0 && hints.getQueue().isEmpty();}
    private static final class Lane {boolean active;final ArrayDeque<Task> tasks=new ArrayDeque<>();}
    private static final class Task {
        final Runnable action,rejected,cancelled;final CompletableFuture<Void> result;ScheduledFuture<?> expiry;
        Task(Runnable action,Runnable rejected,Runnable cancelled,CompletableFuture<Void> result){this.action=action;this.rejected=rejected;this.cancelled=cancelled;this.result=result;}
    }
}
