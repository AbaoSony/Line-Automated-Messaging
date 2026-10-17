package com.example.line.ai.generation.transport;

import java.util.concurrent.CountDownLatch;

/** 将策略/正文工作与真实 HTTP 绑定，等异步分发/传输/回调退出才归还额度；禁止记录或序列化作用域。 */
public final class ModelCallScope implements AutoCloseable {
    /** 本地关联标识在传输层剥除，不发往远端，不包含用户或凭证。 */
    public static final String HEADER="X-Local-Model-Scope";
    private static final int MAX_SCOPES=4096;
    private static final java.util.Map<String,ModelCallScope> ACTIVE=new java.util.HashMap<>();
    private final String id=java.util.UUID.randomUUID().toString();
    private Runnable cancel;
    private CountDownLatch finished;
    private final CountDownLatch sdkFinished=new CountDownLatch(1);
    private boolean resultTracked;
    private boolean closed;
    private ModelCallScope(){synchronized(ACTIVE){
        if(ACTIVE.size()>=MAX_SCOPES)throw new IllegalStateException("Model call scope capacity exceeded");ACTIVE.put(id,this);
    }}
    /** @return 当前单次物理调用的作用域，不保存业务内容或凭证 */
    public static ModelCallScope open(){return new ModelCallScope();}
    /** @return 仅供 SDK 异步序列化与本地传输关联的短时标识，传输必须在发起前剥除 */
    public String id(){return id;}
    /**
     * 登记 SDK 异步序列化/解析的真实完成；尚未建立 HTTP 的取消同样不能留下后台作业后提前归还额度。
     * @param result SDK 原始待完成结果，作用域退出前不能自行取消来伪造完成
     */
    public void track(java.util.concurrent.CompletableFuture<?> result){
        resultTracked=true;result.whenComplete((value,failure) -> sdkFinished.countDown());
    }
    static void attach(String id,Runnable cancel,CountDownLatch finished){
        ModelCallScope scope;synchronized(ACTIVE){scope=ACTIVE.get(id);}
        if(scope==null)throw new IllegalStateException("Model transport scope expired");
        synchronized(scope){
            if(scope.closed || scope.finished!=null)throw new IllegalStateException("Model transport requires one live call scope");
            scope.cancel=cancel;scope.finished=finished;
        }
    }
    /** 实际清理未结束时不能因中断伪造空闲；恢复中断标记，删除仍可按自身上限明确失败。 */
    @Override public void close(){
        synchronized(this){if(closed)return;closed=true;}
        boolean interrupted=Thread.interrupted();
        try {
            if(finished!=null){
                if(finished.getCount()!=0)cancel.run();
            }
            if(resultTracked)while(true)try{sdkFinished.await();break;}catch(InterruptedException failure){interrupted=true;}
            if(finished!=null)while(true)try{finished.await();break;}catch(InterruptedException failure){interrupted=true;}
        } finally {
            cancel=null;finished=null;
            synchronized(ACTIVE){ACTIVE.remove(id);}
            if(interrupted)Thread.currentThread().interrupt();
        }
    }
    /** @return 不含凭证或业务内容的固定说明 */
    @Override public String toString(){return "ModelCallScope";}
}
