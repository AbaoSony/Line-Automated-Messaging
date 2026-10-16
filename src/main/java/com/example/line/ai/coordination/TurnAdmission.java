package com.example.line.ai.coordination;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** 单条入站的准入结果及原轮次业务完成阶段；拒绝不触发模型或技术提示。 */
public record TurnAdmission(boolean admitted, boolean duplicate, UUID turnId,
                             CompletionStage<TurnResult> completion) {    static TurnAdmission rejected(TurnState state, TurnResult.Reason reason) {
        return new TurnAdmission(false, false, null, java.util.concurrent.CompletableFuture.completedStage(new TurnResult(state, reason)));
    }
}
