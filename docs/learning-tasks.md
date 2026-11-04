# Redis 学习任务

Epic 的学习主线是 Redis。通用 CRUD、实体、Mapper XML、分页、基础校验和 SQL 已作为底座提供，不再布置基础 CRUD 开发任务。

详细开发步骤、入口和验收点以 [业务开发文档](业务开发文档.md) 为准。

## 指定学习笔记

用户指定的学习笔记是 [Kyle 的《Redis实战篇》](https://cyborg2077.github.io/2022/10/22/RedisPractice/)。本项目讨论“学习笔记”时，优先对照这篇文章的对应章节，并结合当前代码判断练习位置。

- [Redisson锁的MutiLock原理](https://cyborg2077.github.io/2022/10/22/RedisPractice/#Redisson锁的MutiLock原理)：该节练习的是在三个独立 Redis 实例上分别获取同一业务锁，再组合为 MultiLock，全部获取成功才执行业务。
- 对应项目入口为 `ReservationSubmitFacade` 的用户锁；可以保留用户级锁名，扩展为三个 Redis 客户端上的联锁。此前讨论的“用户锁＋时段锁”属于另一种多资源协调练习，不是该节的主要目标。
- 先在隔离测试中验证全部成功、部分失败后的释放、续期和实例不可用，再考虑接入预约流程。数据库条件更新、唯一约束和事务仍需保留。
- 笔记使用 Redisson 3.13.6，当前项目使用 3.45.1；源码细节与 API 支持情况应以当前版本为准。

## 练习顺序

1. String/Hash/TTL：邮箱验证码与 Token 登录态。
2. Cache Aside、空值缓存、随机过期、Redisson：站点缓存与一致性。
3. GEO：附近充电站与索引维护。
4. Snowflake/Set：订单 ID、重复预约与请求幂等（雪花发号已实现）。
5. Lua：名额原子校验、预占与结果处理。
6. Stream：消费者组、重复消费、确认、重试与接管。
7. 取消补偿：名额释放与 Redis/MySQL 最终一致性。
8. Set：关注同步与共同关注。
9. ZSet：动态 Feed 与滚动分页。
10. Bitmap/HyperLogLog/ZSet：活跃、UV 与热门排行。
11. Redis 并发测试及故障恢复演练。

## 代码边界

- 基础 CRUD 在 ServiceImpl/Mapper 中已保留，直接复用。
- Controller 按骨架要求仍全部 `return null;`；实现 Redis 功能时接通相关入口即可，不把接口接线单列为学习阶段。
- 预约提交、取消、消费和恢复仍是待实现的业务编排；它们属于 Redis 预约练习，不意味着订单状态机已经完成。
- Redis 工具、Lua 和任务实现均待完成；不直接开启空任务。
- 两个登录相关拦截器已在 `WebMvcConfiguration.addInterceptors` 中注册并直接生效；Redisson 随应用启动创建，两者均无额外启用开关。
- Redis 仍使用原项目的 `gc:*` 命名，务必与 GridCharge 隔离 Redis 数据库/实例，或统一改成专属前缀。
