package com.example.line.user;

import com.example.line.common.execution.UserWorkTracker;
import com.example.line.common.logging.*;
import com.example.line.common.persistence.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** 在原持久化池上串行同用户的状态变更和短时 AI 准入；只保存有界在途工作，不缓存权威状态。 */
@Component
class UserLifecycleQueue implements SmartLifecycle,UserDataPurger {
    private static final int EXPIRY_WORKERS=1;
    private final Executor executor;
    private final PersistenceProperties properties;
    private final UserWorkTracker tracker;
    private final Map<String,ArrayDeque<Task>> users=new HashMap<>();
    private int pending;
    private boolean accepting=true;
    private final ScheduledThreadPoolExecutor expiry=new ScheduledThreadPoolExecutor(EXPIRY_WORKERS,r -> {
        var thread=new Thread(r,"line-user-admission-expiry");thread.setDaemon(true);return thread;
    });

    UserLifecycleQueue(@Qualifier("persistenceExecutor") Executor executor,PersistenceProperties properties,UserWorkTracker tracker) {
        this.executor=executor;this.properties=properties;this.tracker=tracker;
        expiry.setRemoveOnCancelPolicy(true);
    }
    <T> CompletionStage<T> submit(String user,String operation,Supplier<T> action) {
        return submit(user,operation,action,null,() -> {});
    }
    <T> CompletionStage<T> submit(String user,String operation,Supplier<T> action,
                                  com.example.line.common.execution.Deadline deadline,Runnable release) {
        var result=new CompletableFuture<T>();
        var lease=tracker.register(user);
        if(lease==null)return CompletableFuture.failedStage(new CancellationException("User lifecycle blocked"));
        Task task=new Task();
        task.run=new MdcTaskDecorator().decorate(() -> {
            try(var scope=LogContext.user(user)) {
                if(result.isDone() || tracker.blocked(user))throw new CancellationException("User admission no longer active");
                result.complete(action.get());
            } catch(RuntimeException failure) {
                try(var scope=LogContext.user(user)){failure(operation,failure);}result.completeExceptionally(failure);
            } finally {release.run();if(task.expiry!=null)task.expiry.cancel(false);try{finished(user,task);}finally{lease.close();}}
        });
        task.rejected=new MdcTaskDecorator().decorate(() -> {
            release.run();if(task.expiry!=null)task.expiry.cancel(false);
            var failure=new RejectedExecutionException("User lifecycle queue unavailable");
            try(var scope=LogContext.user(user)){failure(operation,failure);}
            result.completeExceptionally(failure);
        });
        task.close=lease::close;
        boolean first;
        synchronized(this) {
            if(!accepting || pending>=properties.queueCapacity()){try{task.rejected.run();}finally{lease.close();}return result;}
            var lane=users.computeIfAbsent(user,ignored -> new ArrayDeque<>());
            if(deadline!=null)task.expiry=expiry.schedule(new MdcTaskDecorator().decorate(() -> {
                release.run();
                if(result.completeExceptionally(new CancellationException("User admission deadline")))
                    try(var scope=LogContext.user(user)){BusinessLog.write(LoggerFactory.getLogger(getClass()),Level.INFO,
                            LogEvents.AI_REPLY_SKIPPED,"User admission deadline expired",
                            BusinessLog.fields("reason","lifecycle_deadline","outcome","SKIPPED"));}
            }),deadline.remaining().toNanos(),TimeUnit.NANOSECONDS);
            first=lane.isEmpty();lane.addLast(task);pending++;
        }
        if(first)dispatch(user,task);
        return result;
    }
    private void dispatch(String user,Task task) {
        while(task!=null) {
            try{executor.execute(task.run);return;}
            catch(RejectedExecutionException failure){
                Task rejected=task;
                try{rejected.rejected.run();}finally{task=remove(user,rejected);rejected.close.run();}
            }
        }
    }
    private void finished(String user,Task task) {
        Task next=remove(user,task);
        if(next!=null)dispatch(user,next);
    }
    private synchronized Task remove(String user,Task task) {
        var lane=users.get(user);
        if(lane==null || lane.peekFirst()!=task)throw new IllegalStateException("Lifecycle order violated");
        lane.removeFirst();pending--;Task next=lane.peekFirst();if(next==null)users.remove(user);notifyAll();return next;
    }
    private void failure(String operation,RuntimeException failure) {
        if(failure instanceof CancellationException){
            BusinessLog.write(LoggerFactory.getLogger(getClass()),Level.INFO,LogEvents.EXECUTOR_REJECTED,
                    "User lifecycle work cancelled",BusinessLog.fields("operation",operation,"reason","lifecycle_cancelled","outcome","SKIPPED"));return;
        }
        BusinessLog.failure(LoggerFactory.getLogger(getClass()),Level.WARN,
                failure instanceof RejectedExecutionException?LogEvents.EXECUTOR_REJECTED:LogEvents.PERSIST_FAILED,
                "User lifecycle operation did not complete",failure,
                BusinessLog.fields("operation",operation,"outcome",failure instanceof CancellationException?"SKIPPED":"FAILED"));
    }
    /**
     * 删除屏障已排空时不应留有用户闭包；不把队列清理混入数据库事务。
     * @param userId 完整用户标识
     * @throws IllegalStateException 仍有在途任务时抛出
     */
    @Override public synchronized void purge(String userId) {
        if(users.containsKey(userId))throw new IllegalStateException("User lifecycle has not drained");
    }
    /** 数据库工作复用已有持久化池，独立计时器只释放过期准入引用。 */
    @Override public void start() {}
    /** @return 是否仍允许短时生命周期准入 */
    @Override public synchronized boolean isRunning(){return accepting;}
    /** @return 先于 AI 停止、晚于 Webhook 有序入口排空 */
    @Override public int getPhase(){return DrainingTaskExecutor.WEBHOOK_PHASE-1;}
    /**
     * 停止新准入并限时排空短时工作，之后 AI 才停止，持久化池此时保持可用。
     * @throws IllegalStateException 实际工作未排空或等待中断时抛出
     */
    @Override public synchronized void stop() {
        accepting=false;long end=System.nanoTime()+properties.shutdownTimeout().toNanos();
        while(pending>0) {
            long left=end-System.nanoTime();
            if(left<=0){shutdownFailed();throw new IllegalStateException("User lifecycle shutdown did not drain");}
            try{TimeUnit.NANOSECONDS.timedWait(this,left);}
            catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException("User lifecycle shutdown interrupted");}
        }
        expiry.shutdownNow();
    }
    private void shutdownFailed(){BusinessLog.write(LoggerFactory.getLogger(getClass()),Level.ERROR,LogEvents.EXECUTOR_REJECTED,
            "User lifecycle shutdown did not drain",BusinessLog.fields("reason","shutdown_not_drained","outcome","FAILED"));}
    synchronized boolean isIdle(){return pending==0;}
    void bindAiAdmission(String user){tracker.bindAiAdmission(user);}
    void cancelAi(String user){tracker.cancelAi(user);}
    private static final class Task {Runnable run,rejected,close;ScheduledFuture<?> expiry;}
}
