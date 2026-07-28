# VS Code 与 Metals

> 状态：运行手册
> 事实来源：`build.sbt`、`project/build.properties`、`.vscode/` 与 Metals 官方文档

在独立的 `zyblw-agent/` checkout 根目录打开 VS Code；不要把它作为 `zyblw-platform` 的嵌套文件夹打开。这样
Metals 的 workspace、Bloop build target、sbt 2.0.1 和框架的公开依赖边界都保持独立。

## 首次配置

1. 使用 JDK 21。Agent 的 `.mise.toml` 和构建文件是版本事实来源；先在仓库根目录运行 `mise install`。macOS 上从 Dock
   打开的 VS Code 不一定继承终端的 `JAVA_HOME`：执行 `mise where java`，把结果作为 VS Code **User Settings**（不要写入
   本仓库）的 `metals.javaHome`。随后运行 **Metals: Doctor**，确认 build server 使用 JDK 21，而不是系统的其他 Java。
2. 安装推荐的 `Scala (Metals)` 扩展，然后打开任意 `modules/**/src/**/*.scala` 文件。
3. 在 Metals 弹出的提示中选择 **Import build**。sbt 项目默认经 Bloop 导入，不需要预先安装 Bloop CLI，也不要把
   `sbt-bloop` 永久写入 `project/plugins.sbt`。
4. 导入完成后等待当前文件的 build target 编译成功，再使用跳转、引用、测试和格式化功能。

`.vscode/settings.json` 只包含可共享的格式化和缓存排除规则；`.vscode/tasks.json` 通过 `mise` 提供 compile、`testFull` 与
Scalafmt 检查入口，保证终端也使用 JDK 21。个人 `launch.json`、Java 路径、环境变量和本机调试参数应保留在未跟踪的本地文件中。

## 生成物与重新导入

Metals 会生成 `.metals/`、`.bloop/`、`.bsp/`、`project/metals.sbt`，在多层 sbt build 下还可能生成
`project/project/metals.sbt`。它们是本机缓存或导入桥接文件，已被 `.gitignore` 忽略，绝不提交。

- 改动 `build.sbt`、`project/*.scala`、模块结构或依赖版本后，运行 **Metals: Import build**。
- 跨仓联调切换 `ZYBLW_AGENT_SOURCE_DIR` 的方式时，关闭旧 VS Code 窗口，以该环境变量启动新窗口后再 Import build；不要让
  同一 Metals workspace 在两个依赖模式之间复用旧 Bloop 配置。
- 导入异常时先运行 **Metals: Doctor**，然后 **Metals: Restart build server**。只有缓存确定损坏时才关闭 VS Code 并删除
  本机生成物，再重新 Import build。

## 日常验证

保存 Scala 文件会触发当前 build target 的增量诊断；提交前仍以终端或 VS Code Task 的真实 sbt 门禁为准：

```bash
sbt -batch 'scalafmtCheckAll; scalafmtSbtCheck; testFull'
RUN_POSTGRES_INTEGRATION=1 sbt -batch postgres/testFull
```

第二条会启动 PostgreSQL Testcontainers，只在 Docker 可用且确实需要数据库契约验证时运行。发布前再遵循
[发布与回滚](releasing.md)，不要把 Metals 的成功导入当作发布验证。
