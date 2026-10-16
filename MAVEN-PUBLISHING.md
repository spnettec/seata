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

# Maven 发布与公开静态备用源

默认 `mvn deploy` 只发布现有 YOFC 私库：
release 为 `https://mesmaven.yofc.com/repository/yofc-release/`，
snapshot 为 `https://mesmaven.yofc.com/repository/yofc-snapshot/`。

显式 `-Pgithub` 将发布目标切换为本地 Maven 静态暂存目录：
`target/github-maven-repository/releases` 和 `snapshots`。
可用 `-Dgithub.maven.staging=/absolute/path` 指定目录。
**这个步骤只暂存文件；将审阅后的文件普通 Git 提交推送才会公开发布。**
一次 Maven 调用只有一个发布目标，不自动双发。不要全局常驻激活 github profile，
也不要同时传入 altDeploymentRepository 等覆盖发布目标的参数。

公开目标为 [spnettec/maven-repository](https://github.com/spnettec/maven-repository)，
沿用 `master` 分支的 `releases/`、`snapshots/` 布局：

- Release 下载：`https://raw.githubusercontent.com/spnettec/maven-repository/master/releases/`
- Snapshot 下载：`https://raw.githubusercontent.com/spnettec/maven-repository/master/snapshots/`

这是公开静态文件仓库，**匿名下载，不需要 GitHub Packages token**。
Raw URL 只能下载，不能作为 Maven deploy 上传地址。Git 推送使用操作者已有正常授权；
本配置不会创建凭据或改变权限。过去的 GitHub Packages 地址不再用于本流程。

## 发布操作

以下操作需具备发布授权，从工程根目录执行，并保留所需的项目构建参数：

```sh
# 默认仅私库
mvn deploy
# 显式暂存，尚未公开；无需 GitHub server 凭据
mvn -Pgithub deploy
# 两种模式只读检查，不触发发布
mvn help:effective-pom -Doutput=/tmp/effective-default.xml
mvn -Pgithub help:effective-pom -Doutput=/tmp/effective-github.xml
```

随后将暂存目录中的已批准制品合并到 `maven-repository` 本地 checkout 的
`releases/` 或 `snapshots/`，保留其他文件，不清空目录。审阅具体文件、许可证、
POM/JAR 内容和校验和后，普通 Git commit 并 push 到 master；不得 force-push。
只添加明确的制品路径及校验和，不使用包含缓存、凭据或构建输出的宽泛添加操作。
推送后必须用匿名 GET 回读并核对 SHA-256。

`github` profile 不改变 groupId、artifactId、version 或构建插件。
同 GAV 在两源必须表示同一文件内容；两次构建可能包含不同时间戳，不能假定字节一致。
对于私库已存在的版本，优先只读下载其原始 POM/JAR/附件，验证后原样复制，
而不是重新构建再覆盖。保留父 POM、BOM、传递依赖及适用 LICENSE/NOTICE。
不要公开无关私库制品、凭据、内部部署配置或带 Git LFS 指针的伪制品。

用户已确认私库允许覆盖发布；这不是对私库 redeploy 的实测保证。
静态仓同版本更新使用普通 Git 提交，可审阅并保留历史，版本由维护者决定；
不会自动升版或改为 SNAPSHOT，也不需要删除 GitHub Packages。
覆盖时须同步两源内容并审阅刷新摘要，避免旧缓存和 Raw CDN 缓存造成混用；
若刚推送后的回读仍是旧内容，应等待传播后重新验证，不能降低校验。
GitHub 对普通 Git 文件在 50 MiB 警告、100 MiB 阻止；同版本二进制更新会累积历史，
需持续关注仓库规模，不将超限文件改成 LFS 指针后冒充可直接下载的 Maven 文件。

## CI 读取与内容锁

`.github/scripts/select_maven_source.py` 读取 `.github/fork-artifacts.json`：
私库优先，连接限时5秒、单文件总限时30秒，不重试。仅 DNS、连接、超时、
传输中断时丢弃整批部分结果并回退上述匿名静态 release 地址。
401/403、404、5xx、TLS、SHA-256 不一致均失败，不掩盖权限、缺包或内容冲突。
GitHub 失败同样失败；编译/测试失败不会触发换源或跳过测试。

私库可读取已有 YOFC_MAVEN_USERNAME / YOFC_MAVEN_PASSWORD（如果需要）。
备用源不发送 Authorization，即使环境中存在 token 也不使用。
校验后的文件放入本次临时 file repository，仅镜像 yofc-maven-public 读取源；
每次使用新 localRepository，公共依赖仍从 Central 等既有源解析。
临时 settings 无凭据、不激活发布 profile，也不改变默认发布目标。
Seata Checkstyle 步骤先 compile，使新缓存下 reactor 内部依赖可用。

首次依赖发布顺序是 SOFA Bolt 1.6.20.1.B → SOFA JRaft 1.4.1.B →
Nacos 3.3.0.B → Seata 2.8.0.B（若下游需要 Seata 产物）。
本次锁定的最小 CI 外部依赖为 Nacos 的8个及 Seata的24个POM/JAR，含SOFA父POM与
Nacos客户端传递模块；Seata自身从当前reactor构建，不要求先发布自身全部产物。
该清单不是Nacos/Seata完整服务器发行版清单。未列入锁的新依赖须单独审阅。

## 同版本覆盖后的显式摘要刷新

先完成另行授权的两源发布，暂停并发覆盖，保证同坐标文件字节一致。然后在每个消费
仓库根目录执行，使用已有私库读取环境即可，备用源匿名：

```sh
candidate_dir=$(mktemp -d)
python3 .github/scripts/refresh_artifact_lock.py \
  --output "$candidate_dir/fork-artifacts.json"
diff -u .github/fork-artifacts.json "$candidate_dir/fork-artifacts.json"
```

`diff` 返回1表示有待审阅差异。工具只读现有坐标，从私库和静态备用源读取所有文件，
两源 SHA-256 全部一致才生成新候选并列出旧/新摘要，不覆盖当前清单或已有候选。
失败不生成部分清单；检测并发清单修改。刷新时不做单源回退，因为建立新信任基线
需要两源一致；普通CI的网络错误回退规则不变。缺包或备用源仍是旧内容时先解决同步。

审阅确认后手工替换，验证并提交：

```sh
cp "$candidate_dir/fork-artifacts.json" .github/fork-artifacts.json
python3 -m unittest discover -s .github/scripts -p 'test_maven_source.py'
python3 .github/scripts/select_maven_source.py
git diff --check
git diff -- .github/fork-artifacts.json
```

共同依赖应在两个消费仓库分别刷新、核对相同路径摘要一致。若原样复制私库文件，
新候选与现有清单应无差异。刷新工具不会自动发布、提交或推送；普通CI不自动接受变化。

参考：[Maven仓库布局](https://maven.apache.org/repositories/layout.html)、
[Maven deploy用法](https://maven.apache.org/plugins/maven-deploy-plugin/usage.html)、
[GitHub文件大小限制](https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github)。
