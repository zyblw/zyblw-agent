## 目的与范围

- 维护者/使用者问题：
- 不包含的内容：
- 父分支/父 PR（非 stacked change 填 `main`）：
- 合并顺序：

## 公共契约

- [ ] 不改变公开 Scala API、artifact、状态 JSON、HTTP/schema 或 migration。
- [ ] 如有改变，已声明 Stable/Beta/Experimental 状态、Early SemVer 影响、升级方式与回滚路径。
- [ ] 演进期破坏式变更已进入下一个 minor 的 CHANGELOG；没有为未发布 Experimental API 保留无证据的兼容 shim。
- [ ] 仅有一个业务消费者需要的类型、数据库投影或策略仍留在业务仓库。

## 验证证据

- [ ] `scalafmtCheckAll`、`scalafmtSbtCheck` 与 `testFull`。
- [ ] PostgreSQL 契约测试（持久化或 migration 变更时）。
- [ ] `publishM2` 与独立 Maven consumer 编译（公开依赖面变更时）。
- [ ] 需要业务联调时，已记录 Platform 的源码/Maven-local/Central 下游验证结果。
- [ ] 已说明未运行的门禁及原因。

## 交付

- [ ] 已更新 CHANGELOG 与唯一 canonical 文档（如有用户可见变化）。
- [ ] 不包含私有业务代码、凭据、真实提示词、生产 trace 或用户数据。
- [ ] 新增能力有明确模块边界、稳定度和移除/回滚路径。
- [ ] 最终 diff 不含父 PR 内容、编辑器配置或其它无关改动；合并前已基于最新 `main` 验证。
