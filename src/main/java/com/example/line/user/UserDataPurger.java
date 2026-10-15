package com.example.line.user;

/** 用户数据存储的删除扩展点，由 UserDataDeletionService 调用全部实现以覆盖未来模块。 */
public interface UserDataPurger {
    /**
     * 删除本实现负责的用户数据；操作必须可重复执行。
     * @param userId 非空白完整用户标识
     * @throws org.springframework.dao.DataAccessException 数据库删除失败时抛出
     */
    void purge(String userId);
}
