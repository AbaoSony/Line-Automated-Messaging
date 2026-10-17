package com.example.line.common.execution;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import static org.assertj.core.api.Assertions.*;

/** 验证取消关注后的旧正文/技术提示不能取得发送租约，原文和真实发送结果仍可正常记录。 */
class AiCancellationBoundaryTest {
    @Test void oldAiEpochCannotSendAfterUnfollowOrRefollowButPersistenceAndAlreadyStartedSendingRemainValid(){
        var tracker=new UserWorkTracker();var prior=MDC.getCopyOfContextMap();
        try(var original=tracker.register("Ufictional-epoch")){
            tracker.bindAiAdmission("Ufictional-epoch");String old=MDC.get("ai_work_epoch");
            try(var sending=tracker.registerSending("Ufictional-epoch")){
                assertThat(sending).isNotNull();tracker.cancelAi("Ufictional-epoch");
                assertThat(tracker.registerSending("Ufictional-epoch")).isNull();
                try(var writing=tracker.register("Ufictional-epoch")){assertThat(writing).isNotNull();}
                try(var transport=sending.retain()){assertThat(transport).isNotNull();}
            }
            tracker.bindAiAdmission("Ufictional-epoch");assertThat(MDC.get("ai_work_epoch")).isNotEqualTo(old);
            try(var fresh=tracker.registerSending("Ufictional-epoch")){assertThat(fresh).isNotNull();}
            MDC.put("ai_work_epoch",old);assertThat(tracker.registerSending("Ufictional-epoch")).isNull();
            MDC.remove("ai_work_epoch");try(var manual=tracker.registerSending("Ufictional-epoch")){assertThat(manual).isNotNull();}
        }finally{if(prior==null)MDC.clear();else MDC.setContextMap(prior);}
    }
}
