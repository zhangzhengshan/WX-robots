# 零构建系统，第三方 jar 手工放入 bot/lib

日期：2026-09-12

## 背景

P1 起需要 sqlite-jdbc，后续 P5/P7 还需要 Javalin、JavaMail。工程现状是 run.bat 裸 javac（无 Maven/Gradle），规模基线为单进程、日发送约 100 条消息。

## Decision

不引入 Maven/Gradle。第三方 jar 手工下载放入 `bot/lib/`，run.bat 用 `lib/*` 通配加入 classpath，保持"改完 run.bat 一键编译运行"的现有模式。

## 理由

- 规模极小，依赖个数可枚举（sqlite-jdbc + slf4j、Javalin、JavaMail），没有传递依赖冲突管理的现实需求。
- 引入 Maven 会改变工程结构、运行方式和交付形态（单 jar 约束仍需 shade 插件），收益不抵迁移成本。
- 与现有 json-20240303.jar 的手工管理方式一致。

## Consequences

- 新增依赖必须同时更新 run.bat 注释/清单，jar 文件不入版本库（bot/.gitignore 增加 lib 规则，或仅入小 jar）。
- 代码铺开后再迁 Maven 成本变高；若未来依赖超过 ~10 个或出现传递依赖冲突，应重新评估本决策。

## 修订（2026-09-16 P5）

P5 管理端**不引 Javalin/Jetty**。原计划「P5/P7 还需要 Javalin、JavaMail」改为：

- **P5 管理端 HTTP 服务**：用 JDK 内嵌 `com.sun.net.httpserver.HttpServer`，零新 jar。理由：仅 2 个页面（定时任务 + 知识库）+ 一组 JSON API，原生 HttpServer 足够；Javalin 5.x 引入 Jetty 11 全家桶（约 8 个 jar）+ 传递依赖对齐风险，与零构建手工管理原则冲突。
- **P7 邮件告警**：届时单独评估，可能继续走 JDK `javax.mail`（现 jakarta.mail）或换实现。本 ADR 不预先约束 P7 选型。
