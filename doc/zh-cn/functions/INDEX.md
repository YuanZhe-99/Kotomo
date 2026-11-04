# 函数索引

每个模块和工具一行：它的职责，以及应先阅读的声明。声明在文档注释中带有函数说明层（第一句说明用途；非平凡的声明再写
输入、返回值、副作用和注意事项）。

| 模块 / 文件 | 职责 | 从这里开始 |
|---|---|---|
| `tool/CheckDocs.java` | 文档镜像与链接检查 | `main`、`read` |
| `app/build.gradle.kts` | Android 应用构建：SDK 级别、版本号、R8、从 `key.properties` 读取 release 签名 | `signingConfigs`、`buildTypes` |
| `build-lock.json` | 工具链、上游与数据源的版本及下载哈希 | `toolchain` |
| `.github/workflows/build.yml` | CI：文档检查、Gradle 检查与构建、签名的 release APK；`v*` 标签时创建 GitHub Release | `android`、`release` |
