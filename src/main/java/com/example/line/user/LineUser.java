package com.example.line.user;

import java.time.Instant;

/** 表示 line_user 的关注生命周期快照，与 UserRepository 协作保留原始用户标识。 */
record LineUser(String userId, String status, Instant followedAt, Instant unfollowedAt,
                Instant createdAt, Instant updatedAt) {}
