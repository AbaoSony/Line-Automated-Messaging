package com.example.line.user;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 提供显式手动删除入口，在一个数据库事务中调用所有 UserDataPurger；审计在步骤 3 接入。 */
@Service
public class UserDataDeletionService {
    private final List<UserDataPurger> purgers;

    /**
     * 收集所有用户存储的删除实现，新模块只需注册实现。
     * @param purgers 已注册删除器
     */
    public UserDataDeletionService(List<UserDataPurger> purgers) { this.purgers = List.copyOf(purgers); }

    /**
     * 显式删除用户的全部已接入数据，取消关注流程不会自动调用。
     * @param userId 非空白完整用户标识
     * @throws IllegalArgumentException 用户标识为空时抛出
     * @throws org.springframework.dao.DataAccessException 删除失败时回滚数据库事务
     */
    @Transactional
    public void deleteAll(String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("Missing userId");
        purgers.forEach(purger -> purger.purge(userId));
    }
}
