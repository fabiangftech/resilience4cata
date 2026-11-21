# 发布

resilience4cata 如何发布到 Maven Central。所有步骤都使用 Gradle 自带的 `maven-publish` 和 `signing` 插件；没有使用第三方发布插件。

## 发布的内容 :id=what-is-published

| artifactId | 内容 |
|---|---|
| `resilience4cata-annotations` | jar、源码、Javadoc、POM、Gradle 模块元数据 |
| `resilience4cata-core` | 同上；依赖 `org.jspecify:jspecify`（`compile` scope） |
| `resilience4cata-processor` | 同上；依赖 `resilience4cata-annotations`（`runtime` scope，用于注解处理器路径） |
| `resilience4cata-config` | 同上；依赖 `resilience4cata-core`（`compile`） |
| `resilience4cata-micrometer` | 同上；依赖 `resilience4cata-core` 和 `io.micrometer:micrometer-core`（`compile`） |
| `resilience4cata-testkit` | 同上；依赖 `resilience4cata-core`（`compile`） |
| `resilience4cata-opentelemetry` | 同上；依赖 `resilience4cata-core` 和 `io.opentelemetry:opentelemetry-api`（`compile`，支持的最低版本 1.62.0） |
| `resilience4cata-reactor` | 同上；依赖 `resilience4cata-core` 和 `io.projectreactor:reactor-core`（`compile`，支持的最低版本 3.6.0） |
| `resilience4cata-junit` | 同上；依赖 `resilience4cata-testkit` 和 `org.junit.jupiter:junit-jupiter-api` 5.10.5（`compile`） |
| `resilience4cata-all` | jar（只有 `module-info`）、源码、一个带 README 的 Javadoc jar（javadoc 拒绝处理没有包的模块）、POM、模块元数据；依赖 `core`、`annotations` 和 `config`（`compile`） |
| `resilience4cata-bom` | 只有 POM 和模块元数据：对上面每个模块的约束，版本相同 |

groupId 为 `dev.resilience4cata`。每个 jar 都在 `META-INF` 中带有 `LICENSE` 和 `NOTICE`（Apache-2.0），并且归档是可复现的（没有文件时间戳，顺序固定）。`examples`、`benchmark` 和 `stress` 不发布。

## 构建的组织方式 :id=how-the-build-is-organized

- **`r4c.publish`**（`conventions/.../PublishConventionPlugin.java`），由每个发布的模块应用：一个 `mavenJava` publication，带有 Central 要求的 POM 字段（名称、来自模块 `description` 的描述、url、许可证、开发者、scm、问题跟踪器），签名，以及位于 `build/central-staging` 的 `centralStaging` 文件仓库。
- **`r4c.central`**（`CentralConventionPlugin.java`），由根目录的 `build.gradle` 应用：
  - `centralBundle` 删除 `build/central-staging`，把每个模块发布到其中，进行检查并打包为 `build/central-bundle.zip`。如果版本是 `-SNAPSHOT`、缺少模块、缺少源码或 Javadoc jar，或者任何文件缺少 `.asc` 签名和 `.md5`/`.sha1` 校验和，检查都会失败。只放入发布版本的文件（没有 `maven-metadata.xml`），也不包含 Gradle 同样会生成的 `.sha256`/`.sha512` 校验和：Central 不要求它们，而 bundle 中的每个文件都计入 Central Portal 的每月文件配额（免费计划 1,000 个文件；发布全部模块大约 250 个文件）。
  - `centralUpload` 把 zip 提交到 Central Portal Publisher API（`POST /api/v1/publisher/upload`，`publishingType=USER_MANAGED`）。没有任何任务依赖它，因此 `build` 永远不会上传，并且没有 token 时它会失败。`USER_MANAGED` 部署由 Central 校验，然后在门户中等待，直到有人按下 **Publish**。
- **`apiCompatibility`**（`ApiCompatibilityTask.java`，由 `r4c.publish` 添加，由 `check` 运行，因此 `build` 和 CI 都会运行）：japicmp 将每个发布模块的 jar 与 Maven Central 上 `r4c.apiBaseline`（在 `gradle.properties` 中：上一次发布）版本的同一构件进行比较，包括 public 和 protected 成员，二进制兼容性和源码兼容性。API 是模块在 `module-info.java` 中导出的包，对于没有 `module-info.java` 的模块（`micronaut`、`quarkus:runtime`）则是其构建文件中的 `r4cApi.packages`；不导出任何包的模块（`processor`、`quarkus:deployment`）会被跳过，没有基线的模块（自上次发布以来新增，或离线时）也会被跳过。标注了 `@Internal`（仅为本项目的另一个模块或 `ServiceLoader` 而 public）或 `@Experimental`（尚未定型）的类和成员不计入。对照早于这些注解的基线（0.11.0）时，它们显示为已移除；从带有这些注解的基线开始，它们在两侧都不计入。报告位于 `<module>/build/reports/api-compatibility/api-changes.md`。1.0 之前，不兼容的变更只是警告；从 1.0 开始，除非主版本号变化，否则会导致构建失败。japicmp 通过 Gradle 的 Worker API 运行，因此不增加任何 Gradle 插件。
- **`consumer/check.sh`**（工作流 `consumer`，在涉及构建文件、`module-info.java`、资源或注解处理器的 PR 和 push 到 `main` 时运行）：像用户那样从发布的文件安装 resilience4cata，而构建自身的测试从不读取这些文件（它们使用项目依赖）。它把每个模块发布到 `build/central-staging`（`--staged` 则检查已在那里的内容），检查 BOM 列出的恰好是已发布的构件，然后用 `-Xlint:all -Werror` 构建并运行一个小应用（`consumer/app`：一个带注解的接口和类、一个异步方法、`resilience4cata.properties`），分别使用 Gradle 在 class path 上和作为命名模块（`consumer/gradle`），以及使用 Maven 在 class path 上（`consumer/maven`，独立的本地仓库位于 `build/consumer/m2`），并用两种工具解析 BOM 中的每个构件及其依赖。经过变异检查：把 `config` 作为 `all` 的 `implementation` 依赖、`all` 的 `module-info` 中没有 `config`、BOM 中遗漏某个模块。
- `opentelemetry` 将自身版本报告为 OpenTelemetry instrumentation scope 的版本：`processResources` 把构建的 `version` 写入 `dev/resilience4cata/opentelemetry/version.properties`，因此发布（包括 `-Pversion=…`）不需要手动步骤。
- 没有设置密钥时会跳过签名，因此 `./gradlew build` 和 `./gradlew publishToMavenLocal` 不需要任何机密。

Gradle 属性（或 `ORG_GRADLE_PROJECT_<name>` 环境变量）：

| 属性 | 使用者 | 含义 |
|---|---|---|
| `signingInMemoryKey` | signing | ASCII armor 格式的私钥（`gpg --armor --export-secret-keys <id>`） |
| `signingInMemoryKeyPassword` | signing | 它的口令 |
| `signingInMemoryKeyId` | signing | 可选：签名子密钥的 id |
| `centralPortalUsername`、`centralPortalPassword` | `centralUpload` | Central Portal 用户 token |
| `r4c.scmRepository` | POM | GitHub `owner/repository`，在 `gradle.properties` 中 |
| `r4c.developerId`、`r4c.developerName` | POM | 开发者条目，在 `gradle.properties` 中 |

把机密保存在 `~/.gradle/gradle.properties` 或环境变量中，绝不要放进仓库。开启配置缓存时，Gradle 会把属性值存放在 `.gradle/` 下的（加密）缓存中；在共享机器上签名时请传入 `--no-configuration-cache`。

## 一次性设置 :id=one-time-setup

1. 在 [central.sonatype.com](https://central.sonatype.com) 创建账号。
2. 验证命名空间 `dev.resilience4cata`：在门户中选择 **Namespaces → Add namespace**，然后把它显示的验证密钥作为 DNS TXT 记录添加到 `resilience4cata.dev` 上，再按 **Verify**。
3. 生成用户 token（**View Account → Generate User Token**）。它的两部分分别是 `centralPortalUsername` 和 `centralPortalPassword`。
4. 创建一个签名密钥，并把公钥部分发布到 Central 会检查的密钥服务器（例如 `keys.openpgp.org` 或 `keyserver.ubuntu.com`）：

   ```bash
   gpg --quick-gen-key "resilience4cata releases <you@example.com>" rsa4096 sign 2y
   gpg --keyserver keys.openpgp.org --send-keys <key id>
   gpg --armor --export-secret-keys <key id>     # value of signingInMemoryKey
   ```

5. 对于 GitHub 工作流，创建环境 `production`（Settings → Environments；发布任务声明了它），并向其中添加机密 `SIGNING_KEY`、`SIGNING_KEY_PASSWORD`，以及（若要从 CI 上传）`CENTRAL_PORTAL_USERNAME` 和 `CENTRAL_PORTAL_PASSWORD`。把该环境限制为 `v*` 标签，这样只有发布标签才能读取它们。
6. 在 `gradle.properties` 中把 `r4c.scmRepository`（以及开发者属性）设置为真实的 GitHub 仓库。

## 发布步骤 :id=release-steps

1. 检查自上次发布以来的 API 变更：运行 `./gradlew apiCompatibility`，然后阅读警告和 `*/build/reports/api-compatibility/api-changes.md`；每一个不兼容的变更都要写入变更日志。同时检查生成代码规则（由注解处理器 `1.a` 生成的代码可以在任何 `b >= a` 的 core `1.b` 上运行）：`./gradlew :examples:check -Pr4c.generatedBy=<last release>` 会针对来自 Maven Central 的上一个版本的注解处理器、注解和模块，编译 `examples` 的测试以及由它们生成的代码，然后在本次构建的模块上运行。生成的代码所调用的某个方法如果以二进制不兼容的方式发生了变化，即使源码仍能编译，也会在这里以 `NoSuchMethodError` 失败。CI 在每次变更时针对 `r4c.apiBaseline` 运行它（`-Pr4c.generatedBy=baseline`，即 `build.yml` 的 `generated-code` 任务），因此这一步只需查看其结果。测试源码必须能针对该版本编译：使用了之后新增 API 的测试要放入 `examples/generated-code-exclusions.txt`。[兼容性页面](reference/compatibility.md#how-it-is-checked)上的其他承诺由 `ContractTest` 对照 `contract/` 中的文件检查：自上次发布以来在那里删除的每一行都是一个不兼容的变更。
2. 在 `gradle.properties` 中设置发布版本：`version=0.1.0`。提交（`release 0.1.0`）并打标签：`git tag v0.1.0`，然后推送提交和标签。`main` 只通过检查已通过的 pull request 接受变更（JDK 21 和 25 上的 `build`、`generated code (last release)`、`alloc-gate`、`sonar`；规则集 `default`，它还禁止强制推送和删除分支）；仓库管理员可以绕过它，这正是第 2 步和第 6 步的发布提交可以直接推送的原因。
3. 构建 bundle，两种方式任选其一：
   - 在 CI 中：在标签 `v0.1.0` 上运行 **release** 工作流（`.github/workflows/release.yml`）。它首先在 JDK 21 和 25 上运行完整构建（与 CI 相同的 `build` 工作流，基于打了标签的提交），然后才构建、签名并检查 bundle，对其文件运行 `consumer/check.sh --staged`，把 `central-bundle.zip` 归档为工作流构件，并在设置了 token 机密时上传它；或者
   - 在本地：设置好签名属性后运行 `./gradlew build centralBundle --no-configuration-cache`，然后运行 `consumer/check.sh --staged`。
4. 如果 CI 没有上传，则手动上传：`./gradlew centralUpload -x centralBundle`，或者在门户中选择 **Publish Component**，选中 `build/central-bundle.zip`。
5. 在门户中打开 **Deployments**，等待 *Validated*，检查文件，然后按 **Publish**（或按 **Drop** 丢弃）。构件大约在 30 分钟内出现在 Maven Central 上。
6. 升级到下一个开发版本：`version=0.2.0-SNAPSHOT`，提交。
7. 一旦该版本在 Maven Central 上可见，更新文档和根目录 `README.md` 中写明的版本号（站点上的构建代码片段、落地页、变更日志）：`grep -rn "0\.1\.0" README.md docs/en docs/es`。同时在 `gradle.properties` 中设置 `r4c.apiBaseline=0.1.0`，让 `apiCompatibility` 和生成代码检查与新版本比较，并清空 `examples/generated-code-exclusions.txt`，其中的测试现在可以针对新版本编译。添加 `config/src/test/resources/upgrade/0.1.0.properties`，一个使用新版本每个键的配置文件（复制上一个文件并加入新增内容）；没有它 `UpgradeTest` 会失败，它会检查来自 Maven Central 的该版本能读取这个文件，并且从此以后每个更新的版本都以相同方式读取它。绝不要为了让测试通过而编辑已有的文件。
8. 从标签创建 GitHub release，以该版本的变更日志章节作为说明（相对链接改为绝对链接：`errors.md#r4c-apt-007` → `https://resilience4cata.dev/en/errors?id=r4c-apt-007`），并附上一行安装说明：`gh release create v0.1.0 --verify-tag --title 0.1.0 --notes-file notes.md`。候选版本（RC）使用 `--prerelease --latest=false`；一旦安装片段显示了它（第 7 步），用 `gh release edit v1.0.0-RC3 --prerelease=false --latest` 将其标记为 *Latest*（GitHub 的 *Latest* 不能是预发布版本），这样仓库页面显示的就是站点所安装的版本。

若要在不发布任何内容的情况下检查 bundle，可以用一个一次性密钥和一个发布版本号来构建它：

```bash
gpg --quick-gen-key "throwaway <throwaway@example.invalid>" rsa3072 sign 1d
ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --armor --export-secret-keys throwaway@example.invalid)" \
ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=<passphrase> \
./gradlew centralBundle -Pversion=0.1.0 --no-configuration-cache
unzip -l build/central-bundle.zip
```
