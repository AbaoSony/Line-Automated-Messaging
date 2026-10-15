package com.example.line.user;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 控制事务外删除等待的上限，超时明确失败，可在依赖恢复后重试。 */
@ConfigurationProperties("line.user-deletion")
record DeletionProperties(@DefaultValue("20s") Duration timeout) {
    DeletionProperties {if(timeout==null || timeout.isZero() || timeout.isNegative())throw new IllegalArgumentException("Invalid user deletion timeout");}
}
