package com.example.line.ai.coordination;

import com.example.line.ai.aggregation.TurnSnapshot;
import com.example.line.common.execution.Deadline;

/** 轮次执行适配入口；第二阶段只验证调度，后续编排接入策略、正文与发送。 */
@FunctionalInterface
public interface TurnWork {
    /**
     * 在模型执行线程处理冻结快照；通过协调器返回建议或原子取得发送权并报告结果。
     * 只有实际调用及本地清理全部结束后才能返回，取消 Future 不代表可以提前归还资源。
     * @param ticket 当前程序绑定版本
     * @param snapshot 借用的只读业务快照，方法返回后由调度器关闭，不能保留引用
     * @param deadline 本次有效阶段截止，不得重新分配完整预算
     * @throws RuntimeException 实际执行失败时抛出，由协调器分类为明确业务终态或规则回退
     */
    void execute(TurnTicket ticket, TurnSnapshot snapshot, Deadline deadline);
}
