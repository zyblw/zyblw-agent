# ADR-0015：独立公开仓库与私有业务消费边界

> 状态：决策记录  
> 最后核验：2026-07-26  
> 事实来源：`build.sbt`、`.github/workflows/*.yml`、`docs/releasing.md`、独立 Maven consumer

## 决策

`zyblw-agent` 作为独立公开仓库 `zyblw/zyblw-agent` 维护；`zyblw-server` 与 `zyblw-web` 留在私有
`zyblw/zyblw-platform` 业务仓库。

- 公共框架不依赖、读取或测试私有业务源码。
- 私有 server 在 CI 与生产中只消费 Maven Central 的精确版本。
- 本地跨仓框架开发可以使用 sibling checkout 或 Maven-local，但必须是显式开关。
- GitHub Release、Git tag、issue、PR、许可证与安全报告都由公开框架仓库独立拥有。
- 首版 Maven group 保持 `io.github.zyblw`，源码和 SCM 指向 `zyblw/zyblw-agent`。

## 依赖方向

```text
zyblw-agent (public)
  -> ZIO / ZIO HTTP / PostgreSQL driver / optional adapters
  -> public Maven artifacts

zyblw-server (private)
  -> exact zyblw-agent Maven version
  -> private product domain and adapters

zyblw-web (private)
  -> zyblw-server HTTP API
```

公开仓库永远不能反向依赖私有仓库。真实业务发现的通用问题先在框架仓库形成最小契约、测试和发布版本，再由业务仓库升级。

## 为什么现在拆分

原 ADR-0013 在大量跨目录演进期间选择暂留 monorepo，以保留原子修改能力。现在目标已经改变：业务代码必须持续私有，
框架需要公开 issue、贡献者、release 和 Maven SCM。继续单仓会让公开权限、历史安全扫描、tag 和发布 secret 都与业务
生命周期耦合。

仓库分离的代价小于公开整个业务 monorepo 的泄露风险，也小于长期维护 subtree/submodule 的同步成本。

## 首次公开历史

首次公开仓库使用经过扫描的干净源码快照，不把私有 monorepo 历史直接推送到公开仓库。当前 Agent 只有很短的私有历史，
保留提交归属的收益不足以抵消历史中业务信息和已暴露凭据被带出的风险。

公开后的所有变更正常保留完整 Git 历史。私有仓库仍保留拆分前历史，供内部追溯。

## 舍弃方案

- **把 `zyblw/zyblw-platform` 整体改为 public**：会公开 server、web、部署资料和历史，不可接受。
- **长期保留同一私有仓库并从中发布开源制品**：外部用户无法核对 Maven SCM 对应源码，也不能正常贡献。
- **Git submodule**：增加 clone、分支、CI 和版本指针的双重状态；Maven 已经是 JVM 库的交付边界。
- **复制两份 Agent 源码**：会产生无法判断真源的分叉。
- **继续使用旧的个人 Maven group**：会让新项目身份、文档和依赖坐标长期分裂。首版直接使用
  `io.github.zyblw`，并把 Portal 中该 namespace 已验证作为发布硬门禁。

## 迁移完成标准

1. 公开仓库只包含框架源码、公开文档、示例、CI 和发布配置。
2. 公开 CI 不需要私有仓库、私有 token 或真实 Provider key。
3. `v0.1.0` 可生成签名的 binary、source、Scaladoc 和 POM，并从 Maven Central 解析。
4. 私有 server 只使用 `io.github.zyblw` 的精确版本完成测试。
5. 私有仓库不再保留第二份可修改的 Agent 源码。
6. 两个仓库都禁止 force-push/delete `main`，发布 tag 只在公开仓库创建。
