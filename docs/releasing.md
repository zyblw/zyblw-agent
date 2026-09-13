# 开源发布与版本维护

> 状态：运行手册  
> 最后核验：2026-08-28
> 事实来源：`build.sbt`、`project/plugins.sbt`、`.github/workflows/*.yml`、`integration-tests/maven-consumer`

## 两档门禁

当前把“业务能接入”和“公开 Central 发布”分开，避免把没有上线环境的宿主证据写进日常使用条件。

| 档位 | 目的 | 必过 | 不做 |
|---|---|---|---|
| 业务接入 | 业务仓库用 Docker + 自管 PostgreSQL 引入框架 | `scripts/verify-business-ready.sh`：格式、`testFull`、问答契约、证据清单结构。可选 `RUN_POSTGRES_INTEGRATION=1 postgres/testFull`。然后 `publishM2` 得到 `0.9.0-local`，或 `compose.business.yml` 启动 | 长时 soak、主备、PgBouncer、滚动发布、备份 RPO/RTO、SLO owner |
| 公开发布 | annotated tag 上 Maven Central | 现有 release workflow：`testFull`、`publishM2`、Maven consumer、签名、Portal | 在没有宿主环境时不把 7 项证据改成 `verified_host`，也不因此阻塞业务接入 |

业务项目固定精确版本，不要写版本范围或 `SNAPSHOT`。当前全新安装和发布候选均为 `0.9.0`。
annotated tag `v0.9.0` 必须打在已经包含定日 CHANGELOG 条目与 `docs/fresh-install-0.9.0.md` 的 `origin/main` commit 上。见
[0.9.0 全新安装](fresh-install-0.9.0.md)。

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
git tag -a v0.9.0 -m "zyblw-agent v0.9.0"
git push origin v0.9.0
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

- 当前唯一开发与安装版本是 `0.9.0`，核心与 1024 知识各只有一份 V001 空库基线。
- build、CI 和 release 不比较或消费旧版本 artifact；旧 tag 与 Central 制品只作为不可变发布记录存在。
- 当前使用 early SemVer。`0.9.0` 发布后，后续 `0.9.x` 以它为二进制和源码兼容基线；破坏性变化进入新的 minor。
- Provider、Beta/Experimental 模块跟随统一版本，减少多模块组合矩阵。
- `modules/agent-dashboard` 随仓库打 tag，但不发布 Maven 制品。
- 独立 Maven consumer 仍保留，用来验证当前候选的 POM、资源和 source/doc JAR，不构成业务运行的第二版本路径。

## 发布前清单

1. CHANGELOG 中有用户可理解的变化、升级方式和风险。
2. annotated tag、CHANGELOG 顶部版本与 `docs/fresh-install-0.9.0.md` 一致，且 tag commit 已经包含在远端 `main`；
   release workflow 会通过 `.github/scripts/verify-release.sh` fail-closed 校验。
3. `sbt -batch testFull` 成功。
4. `RUN_POSTGRES_INTEGRATION=1 sbt -batch postgres/testFull` 成功。
5. `integration-tests/command-worker-kill-recovery.sh --restart-postgres` 与 `integration-tests/workflow-wake-worker-kill-recovery.sh --restart-postgres` 均成功，旧/新 JVM PID 不同且数据库重启后 generation 按预期接管。
6. `integration-tests/durable-worker-soak.sh` 成功；全部 Run/command 完成、多个 Worker/lane 实际参与、无 reclaim/retry/过期/死信、最终队列归零，且 P95 回归阈值通过。
7. `integration-tests/workflow-wake-worker-soak.sh` 成功；wake/execution claim 与终态 cycle 一一对应，多个独立 Store/Worker 参与，无 generation reclaim、abandon、lease loss 或失败；正式 wake queue 快照的 due/expired/final-depth 门禁与 P95 回归阈值通过。
8. `sbt -batch publishM2` 成功，所有公开模块生成 POM/source/doc。
9. `integration-tests/maven-consumer` 设置 `ZYBLW_AGENT_VERSION` 后仅依赖本地发布物也能编译。
10. 空 PostgreSQL 上核心与 1024 knowledge V001 可幂等执行，结构、维度、权限与注释审计全部通过。
11. 启用控制台时，`modules/agent-dashboard` 的 `typecheck`、`lint`、`build` 与 Playwright 浏览器契约全部通过。
12. POM 包含 name、description、URL、license、developer 和 SCM。
13. 无密钥、真实用户数据或敏感 trace 进入 Git 历史和 artifact。
14. 私有业务仓库使用同一固定 commit 的 sibling 源码候选完成空库与真实业务回归；私有源码、token 和日志不得进入公开 workflow。
15. Central Portal 显示 Published 后，验证 Maven consumer 能从 Central 解析当前精确版本。

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
