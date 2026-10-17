package com.example.line.ai.decision;

import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/** 全局常量空间熔断器，无用户映射；冷却后仅一探测，旧代次或取消不改变故障计数。 */
@Component
public class DecisionCircuit {
    /** 结果未知/取消和版本替代使用 IGNORED，不将程序取消记成上游故障。 */
    public enum Outcome { SUCCESS, FAILURE, IGNORED }
    /** 不含用户信息的探测代次，防止旧并发结果关闭新的熔断。 */
    public record Permit(long epoch,boolean probe) {}
    private final DecisionProperties properties;
    private final LongSupplier clock;
    private long epoch,openedAt;
    private int failures;
    private boolean open,probing;
    /**
     * 使用生产单调时钟。
     * @param properties 连续故障阈值和冷却
     */
    @org.springframework.beans.factory.annotation.Autowired
    public DecisionCircuit(DecisionProperties properties){this(properties,System::nanoTime);}
    DecisionCircuit(DecisionProperties properties,LongSupplier clock){this.properties=properties;this.clock=clock;}
    /** @return 允许调用时的代次，熔断/已有探测时为空 */
    public synchronized Permit acquire(){
        if(!open)return new Permit(epoch,false);
        if(probing || clock.getAsLong()-openedAt<properties.cooldown().toNanos())return null;
        probing=true;return new Permit(epoch,true);
    }
    /**
     * 在上游真正结束且程序版本核对后登记结果。
     * @param permit 本次取得的许可
     * @param outcome 已核对的成功、故障或忽略
     */
    public synchronized void complete(Permit permit,Outcome outcome){
        if(permit==null || permit.epoch()!=epoch)return;
        if(permit.probe()){
            probing=false;
            if(outcome==Outcome.IGNORED)return;
            epoch++;failures=0;open=outcome==Outcome.FAILURE;
            if(open)openedAt=clock.getAsLong();return;
        }
        if(outcome==Outcome.SUCCESS)failures=0;
        if(outcome==Outcome.FAILURE && ++failures>=properties.failureThreshold()){
            epoch++;open=true;probing=false;openedAt=clock.getAsLong();
        }
    }
    /** @return 是否处于熔断或探测状态，不提供用户标签 */
    public synchronized boolean isOpen(){return open;}
}
