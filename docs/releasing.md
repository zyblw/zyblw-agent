# 开源发布与版本维护

> 状态：运行手册  
> 最后核验：2026-08-09
> 事实来源：`build.sbt`、`project/plugins.sbt`、`.github/workflows/*.yml`、`integration-tests/maven-consumer`

## 发布目标

发布目标是 Maven Central，而不是只上传 GitHub Packages。公开坐标：

```text
groupId:    io.github.zyblw
artifactId: zyblw-agent-<module>_3
version:    Git tag vX.Y.Z
```

GitHub 仍是源码、issue、tag 和 release notes 的事实源；Maven Central 是不可变二进制、POM、source JAR 和 Scaladoc JAR
的分发源。

源码仓库为 `https://github.com/zyblw/zyblw-agent`。首版使用 `io.github.zyblw`；只有 Central Portal 中该
namespace 已显示为 **Verified** 时才能发布。若 `zyblw` 是 GitHub organization 而不是登录 Central Portal 的个人
GitHub 用户名，Central 不会自动授予该 namespace，必须按 Portal 给出的方式独立验证。

## 一次性人工准备

以下步骤需要仓库所有者本人完成，不能由代码自动伪造：

1. 使用个人 GitHub 身份登录 Central Publisher Portal，确认 `io.github.zyblw` namespace 已验证。
2. 生成 Central Portal user token；它不是 GitHub 密码。
3. 为本项目创建独立、可轮换的 GPG signing key，并发布公钥；`PGP_SECRET` 推荐保存为 armored 私钥的单行
   base64。发布工作流也兼容直接保存多行 armored 文本，并会在 runner 内规范化后再交给 `sbt-ci-release`。
4. 在 GitHub Actions secrets 设置：
   - `SONATYPE_USERNAME`
   - `SONATYPE_PASSWORD`
   - `PGP_SECRET`
   - `PGP_PASSPHRASE`
5. 在 GitHub 创建 `maven-central` environment，把上述四项保存为 environment secrets；可为该 environment 设置人工批准。
6. 为 release workflow 启用最小权限；普通 pull request 永远不接触发布 secrets。

不要把这些值写进 `.env.example`、sbt 文件、Actions 日志或 issue。
任何曾经粘贴到聊天、issue、日志或截图中的 Portal token 都应先撤销并重新生成，再更新 GitHub secret。
发布 workflow 会按 sbt 官方 Central Portal 格式创建 runner 临时凭据文件，并在发布命令结束后立即删除；文件内容不会进入
Git、Actions cache 或日志。发布步骤还会先停止前序验证启动的持久化 sbt server，确保新的 sbt JVM 在启动时读取仅对发布
步骤开放的 Central secrets；不要为了规避进程复用而把发布凭据暴露给普通测试步骤。

## 发布触发方式

使用与 CHANGELOG、升级指南一致的 annotated tag 触发发布，例如：

```bash
git tag -a v0.6.0 -m "zyblw-agent v0.6.0"
git push origin v0.6.0
```

标签触发 release workflow：

```text
testFull
  -> publishM2（验证 POM/source/doc 与本地消费）
  -> 独立 Maven consumer 编译
  -> 非交互 GPG 签名探针（验证私钥与口令，并为 runner 的 gpg-agent 建立短期缓存）
  -> ci-release（签名并上传 Central Portal）
  -> GitHub Release
```

只有 Central Portal 状态为 Published、Central 能解析 artifact、GitHub Release 创建成功，才能在 README 写“已经发布”。
Central artifact 不可覆盖；失败修复必须用新版本。

## 日常版本策略

- `0.3.0` 是 Agent/Workflow/核心数据库生产基线；它的公共契约和已发布 migration 保持冻结。
- `0.4.0` 建立结构化 RAG、文档 lineage 与独立知识 schema 基线。
- `0.5.0` 是加法型 minor：新增可选管理面、运行时配置覆盖与模型治理，追加核心 `V002`，不改动 0.4 知识 schema。
  `0.5.x` patch 保护公共 Scala API、state JSON、业务 HTTP/OpenAPI 和两套 Flyway history；`/api/v1/admin/**` 是
  显式标记的 Beta 表面，不进入该 patch 承诺。破坏性演进进入下一个 minor。
- `0.6.0` 为新建 RAG 库建立独立的 1024 维基线，并追加核心 `V003` 使 Embedding 缓存按用途隔离。它不把已发布的
  1536 knowledge schema 原地改维度；保留数据的宿主必须新建 snapshot、重建向量并在评测后切换。此公共 API/物理契约变化属于
  minor，不能作为 `0.5.x` patch 发布。
- `1.x`：公共核心、迁移、HTTP 契约和运维承诺达到稳定后再进入。
- Provider、Beta/Experimental 模块也跟随统一版本，减少多模块组合矩阵。
- `modules/agent-dashboard` 是浏览器应用，随仓库一起打标签，但不发布 Maven 制品，也不占用一个新的 artifact 坐标。

当前 build 使用 `early-semver`。所有已发布制品与 tag 保持不可变；真实 `0.6.0` artifact 是后续 0.6 patch 的兼容基线，不能用
空检查或开发分支替代。

在 sbt 2 的 MiMa/version-policy 生态完成当前版本兼容性验证前，现有的独立 Maven consumer 与平台下游回归仍是必须
执行的实际二进制门禁；它们不能证明所有二进制兼容性，但能证明公开 POM、资源和一条真实宿主消费路径。

## 发布前清单

1. CHANGELOG 中有用户可理解的变化、升级方式和风险。
2. annotated tag、CHANGELOG 顶部版本与 `docs/upgrading-to-X.Y.Z.md` 一致，且 tag commit 已经包含在远端 `main`；
   release workflow 会通过 `.github/scripts/verify-release.sh` fail-closed 校验。
3. `sbt -batch testFull` 成功。
4. `RUN_POSTGRES_INTEGRATION=1 sbt -batch postgres/testFull` 成功。
5. `sbt -batch publishM2` 成功，所有公开模块生成 POM/source/doc。
6. `integration-tests/maven-consumer` 设置 `ZYBLW_AGENT_VERSION` 后仅依赖本地发布物也能编译。
7. 0.6.0 必须验证核心 `V001 + V002 + V003` 可在既有 0.5 库上顺序应用，1024 knowledge baseline 在全新专属
   `zyblw_agent_knowledge` schema/history 可幂等重放，且 pgvector 维度与缓存用途隔离在真实 PostgreSQL 上通过；发布后的 patch
   还必须验证代表性升级库，且不得修改已发布 migration。
8. 启用控制台时，`modules/agent-dashboard` 的 `typecheck`、`lint`、`build` 与 Playwright 浏览器契约全部通过。
9. POM 包含 name、description、URL、license、developer 和 SCM。
10. 无密钥、真实用户数据或敏感 trace 进入 Git 历史和 artifact。
11. 私有业务仓库使用相同 Maven-local 版本完成下游兼容验证，但任何私有源码、token 或日志都不进入公开 workflow。
12. Central Portal 显示 Published 后，在私有 `zyblw-platform` 仓库手动运行 `zyblw-server CI`，输入刚发布的精确
    `agent_version`；该回归只从 Maven Central 解析制品，并包含 PostgreSQL 契约测试。

框架的 Scaladoc 会读取多个 source root 的 TASTy；仓库通过 `.jvmopts` 为 sbt 构建 JVM 提供 3 GiB 上限和 G1GC。
CI 不应以更小的 `SBT_OPTS/JAVA_OPTS` 覆盖该基线。若 `packageDoc` 失败，发布必须失败；不能用空 doc JAR 掩盖 API
文档生成缺陷。

## 回滚

Maven Central 发布物不可删除或覆盖。代码回滚不等于依赖回滚：

- 有缺陷的新版本立即在 GitHub Release/README 标记；
- 发布新的 patch 修复；
- 若存在安全风险，发布 advisory 和受影响版本范围；
- 数据库变更优先使用向前修复 migration，不依赖自动 down migration。

## 官方参考

- [sbt Publishing](https://www.scala-sbt.org/1.x/docs/Publishing.html)
- [Maven Central 发布物要求](https://central.sonatype.org/publish/requirements/)
- [Central Portal token](https://central.sonatype.org/publish/generate-portal-token/)
- [sbt-ci-release](https://github.com/sbt/sbt-ci-release)
- [GitHub Actions 安全使用](https://docs.github.com/en/actions/reference/security/secure-use)
