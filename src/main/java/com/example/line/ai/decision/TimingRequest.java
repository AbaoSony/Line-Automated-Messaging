package com.example.line.ai.decision;

import com.example.line.ai.aggregation.InboundMember;
import com.example.line.chat.PrivateHistory;
import com.example.line.common.execution.Deadline;
import java.time.Duration;
import java.util.List;

/** 厂商无关的时机快照；只含当前业务成员、已接受历史、程序年龄/间隔与固定收集余量。 */
public record TimingRequest(List<InboundMember> members,PrivateHistory history,long ageMillis,Long gapMillis,
                            Duration maxWait,Deadline deadline,java.util.UUID turnId,long inputVersion) {
    /**
     * 为独立适配测试构造业务快照，生产另传实际轮次与版本。
     * @param members 全部成员
     * @param history 已接受历史
     * @param ageMillis 轮次年龄
     * @param gapMillis 已知间隔
     * @param maxWait 当前最大等待
     * @param deadline 原截止
     */
    public TimingRequest(List<InboundMember> members,PrivateHistory history,long ageMillis,Long gapMillis,Duration maxWait,Deadline deadline){
        this(members,history,ageMillis,gapMillis,maxWait,deadline,null,0);
    }
    /**
     * 固定成员集合，不能以 SDK 对象或凭证代替业务快照。
     * @param members 全部有序成员
     * @param history 已接受完整历史
     * @param ageMillis 原始入口年龄
     * @param gapMillis 上次聊天间隔，未知为空
     * @param maxWait 原始收集窗口当前余量
     * @param deadline 本次策略与收集共同截止
     * @param turnId 程序实际轮次标识
     * @param inputVersion 程序实际输入版本
     */
    public TimingRequest { members=List.copyOf(members); }
}
