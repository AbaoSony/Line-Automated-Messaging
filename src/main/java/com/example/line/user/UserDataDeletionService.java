package com.example.line.user;

import java.util.List;
import com.example.line.audit.AuditService;
import com.example.line.common.logging.LogEvents;
import java.util.Map;
import org.springframework.stereotype.Service;
import com.example.line.common.execution.UserWorkTracker;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 在单一业务事务中执行全部用户删除器；事务完成后记录真实提交或回滚结果，不因取消关注触发。 */
@Service
@EnableConfigurationProperties(DeletionProperties.class)
public class UserDataDeletionService {
    private final List<UserDataPurger> purgers;
    private final AuditService audit;
    private final List<UserDeletionGuard> guards;
    private final UserWorkTracker tracker;
    private final TransactionTemplate transaction;
    private final DeletionProperties properties;

    /**
     * 收集所有用户存储的删除实现，新模块只需注册实现。
     * @param purgers 已注册删除器
     * @param audit 事务完成后异步记录删除结果
     * @param guards 取消各模块等待任务
     * @param tracker 事务外用户屏障
     * @param manager PostgreSQL 事务管理
     * @param properties 等待上限
     */
    public UserDataDeletionService(List<UserDataPurger> purgers, AuditService audit, List<UserDeletionGuard> guards,
                                   UserWorkTracker tracker, PlatformTransactionManager manager, DeletionProperties properties) {
        this.purgers = List.copyOf(purgers);
        this.audit = audit;
        this.guards=List.copyOf(guards);this.tracker=tracker;this.transaction=new TransactionTemplate(manager);this.properties=properties;
    }

    /**
     * 显式删除用户的全部已接入数据，取消关注流程不会自动调用。
     * @param userId 非空白完整用户标识
     * @throws IllegalArgumentException 用户标识为空时抛出
     * @throws org.springframework.dao.DataAccessException 删除失败时回滚数据库事务
     */
    public void deleteAll(String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("Missing userId");
        try(var barrier=tracker.block(userId)) {
            guards.forEach(guard -> guard.cancelPending(userId));
            // 网络调用和异步写入先在事务外排空，不在持有数据库事务期间长时间等待。
            barrier.await(properties.timeout());
            transaction.executeWithoutResult(status -> purgers.forEach(purger -> purger.purge(userId)));
            barrier.committed();
            audit.record(LogEvents.USER_DATA_DELETED,"manual",null,userId,"SUCCESS",Map.of());
        } catch(RuntimeException failure) {
            audit.record(LogEvents.USER_DATA_DELETED,"manual",null,userId,"FAILED",Map.of("error_type",failure.getClass().getSimpleName()));
            throw failure;
        }
    }
}
