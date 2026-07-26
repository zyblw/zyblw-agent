# ADR-0013：开源发布边界与 Reference Consumer（已被取代）

> 状态：决策记录（已被 [ADR-0015](0015-independent-public-repository.md) 取代）  
> 最后核验：2026-07-24  
> 事实来源：`build.sbt`、发布工作流、`zyblw-server/build.sbt`

## 决策

`zyblw-agent` 保留在当前 monorepo 中，但作为具有独立许可证、Maven 坐标、版本、发布流程和文档入口的子项目。

- Maven group：`io.github.zyblw`
- Scala package：保持 `com.zyblw.agent`
- 版本：Git tag 驱动的统一多模块 early-semver
- 发布：GitHub Actions + sbt-ci-release + Central Portal
- server：默认源码联调；设置版本后只消费发布 artifact
- 数据库：新消费者使用框架专属 migration location/history；既有 server 使用显式兼容位置

## 原因

立即拆仓会让当前大量未提交、跨框架/业务的演进承担额外同步成本；继续只用 ProjectRef 又无法证明公共依赖、POM 和资源是否
完整。双模式让本地反馈与真实下游契约同时存在。

Maven group 使用 GitHub namespace，避免依赖未持有域名；Scala package 暂不重命名，避免一次性破坏全部 imports、状态
schema、日志名和业务集成。坐标与 package 不要求相同。

## 舍弃方案

- 发布一个包含所有依赖的 fat JAR：依赖冲突、漏洞面和用户选择都更差。
- 仅发布 `agent-core`：不能验证真实 Provider、PostgreSQL 和 HTTP 使用路径。
- 让 migration 留在 `db/migration`：会与任何宿主 Flyway 资源混合，属于库的隐式副作用。
- 一开始承诺 1.0：当前 Beta/Experimental 模块缺少足够生产证据。
- 立即拆成 Agent 微服务：网络/部署边界不是库发布的必要条件。

## 代价

monorepo 的 `vX.Y.Z` 标签暂时专用于 agent 发布；业务源码仍与公共框架同仓可见。若未来业务代码不适合公开、发布节奏明显
分离或外部贡献者经常被无关目录干扰，应把 `zyblw-agent` 连同 Git 历史提取到独立仓库，Maven 坐标保持不变。

## 后续门禁

第一版发布后引入与当前 sbt 版本兼容的 MiMa/version-policy；建立 API surface 报告、State/HTTP/schema upgrade fixtures，
并要求 `zyblw-server` 的 artifact-only consumer job 持续通过。
