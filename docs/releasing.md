# 开源发布与版本维护

> 状态：运行手册  
> 最后核验：2026-07-29
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

## 首次发布

首次建议发布 `0.1.0`，明确 API 尚处早期演进：

```bash
git tag -a v0.1.0 -m "zyblw-agent v0.1.0"
git push origin v0.1.0
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

- `0.1.x`：二进制/源码兼容修复，不删除或改变已有公共签名。
- `0.2.0`：允许明确记录的破坏性 API 改进。
- `1.x`：公共核心、迁移、HTTP 契约和运维承诺达到稳定后再进入。
- Provider、Beta/Experimental 模块也跟随统一版本，减少多模块组合矩阵。

当前 build 使用 `early-semver`。第一版发布后应接入可支持当前 sbt 版本的 MiMa/version-policy 门禁；在不存在历史
artifact 时，兼容检查没有可靠基线，不能用一个空检查假装已经完成。

在 sbt 2 的 MiMa/version-policy 生态完成当前版本兼容性验证前，现有的独立 Maven consumer 与平台下游回归仍是必须
执行的实际二进制门禁；它们不能证明所有二进制兼容性，但能证明公开 POM、资源和一条真实宿主消费路径。

## 发布前清单

1. CHANGELOG 中有用户可理解的变化、升级方式和风险。
2. `sbt -batch testFull` 成功。
3. `RUN_POSTGRES_INTEGRATION=1 sbt -batch postgres/testFull` 成功。
4. `sbt -batch publishM2` 成功，所有公开模块生成 POM/source/doc。
5. `integration-tests/maven-consumer` 设置 `ZYBLW_AGENT_VERSION` 后仅依赖本地发布物也能编译。
6. 数据库迁移在空库和代表性升级库成功，已发布 migration 未被修改。
7. POM 包含 name、description、URL、license、developer 和 SCM。
8. 无密钥、真实用户数据或敏感 trace 进入 Git 历史和 artifact。
9. 标签与 CHANGELOG 版本一致，工作树基于已审查 commit。
10. 私有业务仓库使用相同 Maven-local 版本完成下游兼容验证，但任何私有源码、token 或日志都不进入公开 workflow。
11. Central Portal 显示 Published 后，在私有 `zyblw-platform` 仓库手动运行 `zyblw-server CI`，输入刚发布的精确
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
