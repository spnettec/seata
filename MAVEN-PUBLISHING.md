<!--
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Maven 发布目标

默认 `mvn deploy` 保持现有 YOFC 私库：release 为
`https://mesmaven.yofc.com/repository/yofc-release/`，snapshot 为
`https://mesmaven.yofc.com/repository/yofc-snapshot/`。
只有显式选择 `-Pgithub` 才将二者切换到
`https://maven.pkg.github.com/spnettec/seata`（server id：`github`）。
一次调用只有一个发布目标，不自动双发；profile 本身不会触发发布。
不要在全局 settings 的 activeProfiles 中常驻启用 github，也不要同时传入覆盖目标的
`altDeploymentRepository` / `altReleaseDeploymentRepository` / `altSnapshotDeploymentRepository`。

## 用法

以下发布命令仅作为操作说明，需另行批准实际发布并准备已有的安全凭据：

```sh
# 现有私库（沿用项目需要的构建参数）
mvn deploy
# 显式发布 GitHub Packages
mvn -Pgithub deploy
# 只查看配置，不发布
mvn help:effective-pom -Doutput=/tmp/effective-default.xml
mvn -Pgithub help:effective-pom -Doutput=/tmp/effective-github.xml
```

`github` 只覆盖 distributionManagement，不改变 groupId、artifactId、version、依赖、
构建插件或下载仓库。同一模块在两个仓库保持相同 GAV；例如
`com.github.heyoulin.seata:seata-core:2.8.0.B`。
Seata 的根 POM 与独立 build 父 POM 均提供该 profile，覆盖直接继承 build 的 BOM。

相同坐标必须代表相同内容。将同一版本分发到两个目标前，固定源码、依赖和构建输入，
对 JAR、POM、附件逐一核对 SHA-256；单纯重新运行两次构建不能保证字节一致。
若现有坐标内容不同，应停止并由维护者决定版本或重新分发策略；不自动升版或转为 SNAPSHOT。

## 认证、公开性与跨仓库读取

GitHub Maven 包继承承载仓库的可见性和权限，公开目标要求承载仓库公开；
POM 无法将包设为公开，也不改变仓库权限。**公开 Maven 包下载仍需认证**。

- 本地发布：使用已有 PAT (classic)，需要 `write:packages` 及目标仓库写入权限；
  下载需要 `read:packages` 及对应读取权限。不要把 token 写入 POM、文档或 Git。
- Maven settings 中使用 server id `github`，由现有安全机制提供用户名和 token，
  或使用环境变量引用 `${env.GITHUB_ACTOR}` / `${env.GITHUB_TOKEN}`。
- 同仓库 Actions 发布可使用拥有 `packages: write` 的 `GITHUB_TOKEN`。
  Seata 读取 Nacos 是跨仓库访问；本仓库的 token 不会自动获得另一私有仓库权限。
  应验证目标包的实际读取权限；无法访问时使用有目标仓库读取权限的
  `read:packages` PAT (classic)。不要把其他 registry 的 granular package 权限设置
  当作 Maven registry 已支持的能力。
- 消费方须另行配置 `https://maven.pkg.github.com/spnettec/nacos` 等下载 repository
  及相匹配的 server id，必要时为每个下载源配置独立 id；发布 profile 不添加下载源。

## 首次发布与 CI 前置条件

CI 在 `mesmaven.yofc.com` DNS 解析失败时无法取得私有 `nacos-client:3.3.0.B`。
本改动不修复 DNS，不发布依赖，不代表 CI 已修复。

完整依赖链应先准备 SOFA Bolt `1.6.20.1.B`，再 SOFA JRaft `1.4.1.B`，
再 Nacos `3.3.0.B`，最后 Seata `2.8.0.B`。发布父 POM、BOM、所需模块及传递依赖，
不能只上传 nacos-client JAR。若只发布 Nacos client 子图，以 reactor 实际依赖为准。
这些 SOFA fork 的 GitHub 发布配置不在本改动范围内。

后续实际发布需要单独授权、目标写入凭据及可访问现有私有依赖的构建环境。
如果消费端仍使用 YOFC，需修复 runner 的私库网络；如果改用 GitHub，则需先发布完整依赖，
再核实本次 CI 源选择可访问这些制品及其读取认证，并验证无缓存解析和 CI。创建凭据、修改权限及实际发布均不是本配置变更的一部分。

参考：[GitHub Maven registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry)，
[GitHub Packages 权限](https://docs.github.com/en/packages/learn-github-packages/about-permissions-for-github-packages)。

## CI 私库优先与 GitHub 回退

Nacos 的 `fork-ci.yml` 覆盖 heyoulin，Seata 的 build 工作流全量测试任务接入源选择。
`.github/scripts/select_maven_source.py` 逐项读取 `.github/fork-artifacts.json`：
私库优先，连接上限 5 秒、单文件总上限 30 秒，不重试；只有 DNS、连接、超时或
传输中断才切换 GitHub，并丢弃整批私库部分结果。TLS 错误、401/403、404、5xx 和
校验和不一致均直接失败，不以回退掩盖权限、缺包或内容问题。GitHub 失败也直接失败。

两源的每个锁定 POM/JAR 都必须匹配同一 SHA-256。锁来自现有发布版本，已只读回查
YOFC 内容；其中 Nacos 外部 fork 8 个文件，Seata 外部 fork 24 个文件，含父 POM 与
传递 fork 依赖。维护者决定更新版本或同版本内容后，须审核并同步更新锁；不会自动刷新锁。
这会刻意拒绝同 GAV 内容漂移，不将 `-U` 当作 release 缓存一致性保证。

脚本将校验后的文件放入本次运行临时 file repository，仅镜像 `yofc-maven-public`
读取源；其他公共依赖沿用 Central 等配置。每次 Maven 使用新的 localRepository，
不消费恢复缓存中的旧 fork；未列入锁的新 fork 必须显式审核补齐，否则解析失败。
临时 settings 没有凭据、发布覆盖参数或激活 github profile，不改变 deploy 目标。
构建失败不会触发换源或跳过测试。Checkstyle 步骤先 compile，使全新缓存下 reactor 内部依赖可用。

私库凭据如需要，读取已有 `YOFC_MAVEN_USERNAME` / `YOFC_MAVEN_PASSWORD`；
GitHub 读取已有 `PACKAGES_READ_TOKEN`，否则尝试工作流 token。未创建这些 secret，
也未扩大工作流权限。跨仓库读取不满足时会报错，需管理员另行提供已有授权的读取凭据。
来自外部 fork 的 PR 通常拿不到 secret，此时失败是预期行为。

本地非破坏性模拟测试：

```sh
python3 -m unittest discover -s .github/scripts -p 'test_maven_source.py'
```

## 已有 release 版本

`-Pgithub` 只选择仓库，不授予同版本覆盖能力。GitHub Maven 已存在的 release 制品
可能拒绝重复上传（409/422）；本任务没有通过真实 deploy 探测覆盖，也不保证可覆盖。
若实际操作要求先删除旧版本再发布，需单独批准删除范围、保留备份、评估下载中断和
下游缓存不一致，再人工执行；没有自动删除流程。版本变更由维护者决定。
用户已确认 YOFC 私库已开放覆盖发布；本配置任务未通过真实部署验证覆盖成功。
GitHub 同版本覆盖能力仍未验证，不能从私库策略推断 GitHub 行为。

## 同版本覆盖发布后的清单刷新（显式操作）

用户已确认私库允许覆盖发布；这不是本工具对 redeploy 的实测结果。
版本是否变更仍由维护者决定。覆盖同版本后，旧 SHA-256 会使 CI 明确失败；
不能通过删除校验、使用旧缓存或自动接受新内容来消除失败。

1. 在另行授权的发布操作完成后，暂停对这些坐标的并发覆盖，确认两个目标都包含
   同一批 POM/JAR 字节。GitHub 同版本覆盖能力仍未验证；若其拒绝覆盖，先停止，
   由维护者决定后续方案。本工具不会发布、删除包或修改版本。
2. 用已有安全环境提供私库读取凭据（如需要）以及 GitHub 的
   `PACKAGES_USER` / `PACKAGES_TOKEN`（`read:packages` 与对应仓库读权限）。
   本地工具不自动读取 Actions secret `PACKAGES_READ_TOKEN`，不要将凭据写入命令或 Git。
3. 在每个消费仓库根目录显式生成候选清单：

```sh
candidate_dir=$(mktemp -d)
python3 .github/scripts/refresh_artifact_lock.py \
  --output "$candidate_dir/fork-artifacts.json"
diff -u .github/fork-artifacts.json "$candidate_dir/fork-artifacts.json"
```

`diff` 返回 1 表示有待审阅差异。工具只读取现有清单中的路径，不增删坐标、
不变更版本；逐文件从私库和 GitHub 读取内容，使用与 CI 相同的下载超时和认证实现。
仅当每个文件两源 SHA-256 相等，才生成新的候选 JSON，并列出改变的旧/新摘要。
候选文件必须不存在，不能指向当前清单；任何网络、认证、404、TLS 或内容冲突
都会失败并保留原清单，不输出部分候选。刷新时**不进行单源回退**，因为更新信任基线
必须核实两源一致；普通 CI 的“私库优先、仅网络故障回退”规则保持不变。

如果只覆盖了私库、GitHub 仍是旧内容或尚未发布，则无法生成可安全回退的共同清单，
应先处理发布/读取条件，而不是把这一情况静默视为同步完成。

4. 审阅候选差异，确认符合本次已批准的覆盖内容，再由维护者显式替换：

```sh
cp "$candidate_dir/fork-artifacts.json" .github/fork-artifacts.json
python3 -m unittest discover -s .github/scripts -p 'test_maven_source.py'
python3 .github/scripts/select_maven_source.py
git diff --check
git diff -- .github/fork-artifacts.json
```

提交清单后再运行 CI。对共同依赖（例如 SOFA）须在 Nacos 和 Seata 两个消费仓库
分别刷新并审阅，保证同路径摘要一致。工具不会自动提交、推送、调用 Maven，
也不会在普通 CI 中调用刷新逻辑；CI 仅运行模拟刷新测试和已有清单校验。
`--lock <路径>` 可指定显式维护的输入清单；新增依赖路径属于单独审阅范围。
