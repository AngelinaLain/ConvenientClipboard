# Convenient Clipboard

Java 21 + Spring Boot + Redis + Flyway + PostgreSQL + Vue 3 文件分享项目，通过 Maven 管理后端、Docker Compose 部署整个应用。

## 已实现

- 游客、登录用户均可上传文件，生成不可枚举的随机分享链接，默认保留 **7 天**。
- 上传使用8MiB分片和逐片SHA-256校验，支持服务端进度查询、暂停继续、断点续传及重复提交保护。刷新后恢复任务并只补传缺片；浏览器能缓存原文件时自动继续，缓存不足时重新选择原文件即可续传。
- 登录用户可在上传时主动选择永久保存，也可在「我的文件」中切换保存期限、复制链接、删除文件。永久文件改回临时保存时，从修改时间重新计算 7 天。
- 共享页面展示文件信息并下载；下载使用附件响应，过期文件立即禁止访问，后台异步删除。
- 独立管理入口、管理员账号密码登录、JWT HttpOnly Cookie、CSRF 防护、Redis 登录限流与退出后的令牌撤销。
- 管理端按文件名、上传用户或文件ID检索全部文件，组合筛选存储位置和保存方式，选择每页条数与跳转页码；支持删除文件，创建用户、调整角色与启停状态、重置密码、删除无文件用户。
- 管理端查看使用量、动态调整单文件上传上限（MiB/GiB）、本地容量预算及转存阈值，配置多个 S3、NFS、SMB 存储器。
- 文件先写本地；本地使用量达到预算的 **50%** 后，优先迁移永久保存文件，再按剩余有效期从长到短迁移临时文件；同一优先级按上传时间从早到晚、文件ID排序。远端写入并校验大小、提交数据库位置后，再删除本地副本。默认每 30 秒重试维护任务。
- 多存储器按优先级尝试，失败时保留本地文件；停用存储器只停止新增迁移，已有文件仍能下载。

S3 可连接 Amazon S3、MinIO 及其他兼容服务；NFS、SMB 通过宿主机或 Docker 挂载共享目录接入，后台配置挂载路径与标识，应用不持有 NAS 登录凭据。连接方式见 [NFS / SMB 配置](docs/storage-mounts.md)。WebDAV、SFTP、原生 OSS/COS 接口和 SSO 为后续扩展，见 [架构说明](docs/architecture.md)。

## 一键启动

需要启用 BuildKit 的 Docker Engine 和 Docker Compose v2（当前 Docker Desktop 默认支持）。Docker 内构建，无需在宿主机安装 Java、Maven 或 Node。后端使用 BuildKit 缓存 Maven 依赖，下载连接设置超时，便于网络异常后重试构建。

Windows PowerShell：

```powershell
./scripts/init-env.ps1
docker compose up -d --build
```

Linux / macOS：

```sh
sh scripts/init-env.sh
docker compose up -d --build
```

初始化脚本只运行一次，会生成 `.env`，其中管理员初始账号为 `admin`，随机密码见 `APP_ADMIN_PASSWORD`。如果 `.env` 已存在，脚本会停止，避免覆盖数据库密码和存储加密密钥。

| 入口 | 地址 |
| --- | --- |
| 上传前台 | http://localhost:8088 |
| 用户登录/注册 | http://localhost:8088/login |
| 我的文件 | http://localhost:8088/files |
| 管理员登录 | http://localhost:8088/admin/login |
| 管理后台 | http://localhost:8088/admin |

管理员首次启动时创建；之后修改 `.env` 的初始密码不会重置已有账号。

```sh
docker compose ps
docker compose logs --tail 100 backend
docker compose down
```

普通 `down` 保留数据卷。数据库、Redis 和本地文件各自使用持久化卷。

## 配置存储

在后台「远端存储」选择类型。S3 填写端点、区域、桶和访问凭据；桶需要预先创建，凭据需要对应桶的 PutObject、HeadObject、GetObject、DeleteObject 权限。端点必须可从 **backend 容器**访问；容器中的 `localhost` 指容器自身，同宿主服务可使用 `host.docker.internal`（Linux 需配置 host-gateway）。NFS / SMB 填写 `/mnt/remote` 内的挂载目录和 `.clipboard-storage-id` 标识，挂载示例见 [配置说明](docs/storage-mounts.md)。

默认本地预算 10 GiB，阈值 50%。预算是应用层对象存储限额，不会为宿主机分区或预分配空间。未完成的分片任务会按最终文件大小预留预算，旧整文件接口也扣除这些预留，避免并发上传超额。预算不包括 PostgreSQL、Redis、Nginx 和 multipart 临时文件；分片合并时暂存片与最终对象可能同时存在，实际磁盘需预留余量。没有可用远端时，已有文件继续保留；达到本地预算后，新上传返回空间不足，释放空间或恢复远端后可重试。

S3 凭据以 AES-GCM 加密后写入数据库，管理 API 不返回密钥。有文件引用的存储器不能删除或修改类型、端点、区域、桶、挂载路径与标识；可以停用或更新 S3 凭据。迁移保留原分享链接和到期时间。

用户被停用、修改角色或重置密码后，其旧 JWT 即失效。管理员不能禁用、降级或删除自己；系统始终保留至少一个启用的管理员。删除用户之前需要先处理该用户名下的文件。

「我的文件」保存期限切换会直接更新当前行；写请求使用新的 CSRF 校验，明确的校验过期只自动重试一次，普通权限不足不会重试，无需强制刷新。

## 对外部署

`.env` 中设置 `APP_PUBLIC_BASE_URL=https://files.example.com`，用于生成分享链接。默认端口只绑定 `127.0.0.1`；通过本机 HTTPS 反向代理转发到 `8088`。在 HTTPS 环境设置 `APP_COOKIE_SECURE=true`。需要直接开放监听时设置 `APP_BIND_ADDRESS=0.0.0.0`，并按服务器部署要求配置 TLS。

默认单文件最大 1 GiB。在管理后台「容量设置」调整「单文件上传上限」，支持 1 MiB–100 GiB，保存后后续上传立即使用新值，无需重启容器。设置保存在 PostgreSQL，重启不会恢复默认值。`.env` 中的 `APP_MAX_UPLOAD_BYTES` 仅作为后台尚未保存设置时的初始值；兼容旧 API，保存容量设置时省略 `maxUploadBytes` 会保留当前上限。降低上限不会删除或限制已有文件的下载，尚未完成的上传在最终写入时会按最新设置再次校验。

后端 multipart 解析器在每个上传请求开始时读取上限，接收过程中检查单文件及整个请求大小（包括 chunked 请求），临时文件写磁盘；业务写入时再次校验。内置 Nginx 的总请求硬上限为 100 GiB + 1 MiB 封装余量，不再固定卡在旧的 1 GiB。原 `APP_MAX_REQUEST_BYTES` 不再控制上传大小。如果前面还有自己的 Nginx、网关或 CDN，也需允许相应的请求大小与上传时长。大文件仍先落本地，需同时满足本地容量预算和实际磁盘剩余空间；接收临时文件与本地对象短时可能各占一份空间。

前台现在使用分片接口，每次请求最多传一个8MiB分片；原 `POST /api/files` 整文件接口继续兼容。任务与分片记录保存在PostgreSQL，暂存片位于 `APP_STORAGE_PATH/uploads`，与文件对象共用持久化卷，重启后可恢复。未完成任务在10分钟无有效写入后失效，立即释放预留容量，默认每30秒维护清理过期分片；持续成功上传会续期，单纯查询进度不会续期。完成结果和取消/过期记录保留一段时间用于识别重复请求。浏览器本地存储保存任务ID和访问令牌，请使用原浏览器和原账号恢复；页面再次打开或在线查询确认任务失效后，会清理对应的本地原文件缓存与任务记录。浏览器关闭期间无法执行本地清理。清除站点数据会丢失本地恢复凭据，已保存的文件仍按原期限保留。

从旧版升级时，需要一次重建 backend/frontend 来更新上传解析器与 Nginx；此后只在后台调整即可。使用已挂载 NFS/SMB 的部署示例：

```sh
git pull --ff-only
docker compose -f compose.yaml -f compose.mounts.yaml up -d --build
```

备份需同时覆盖 PostgreSQL、`file_data`、远端桶以及 `.env` 中的存储加密密钥。保留同一份 `APP_STORAGE_ENCRYPTION_KEY`，否则已有存储凭据无法解密。首次启动脚本生成随机密钥；`.env` 已在 `.gitignore` 中排除。

## 本地开发与验证

需要 Java 21、Node.js 22.12+。项目内置 Maven Wrapper。IDE 使用 Java 21 SDK；本机 `java` 若指向 Java 8，需要先调整 `JAVA_HOME`。

```powershell
# 后端单元测试及打包
./mvnw.cmd verify

# 前端类型检查与生产构建
cd frontend
npm ci
npm run build
```

开发时为 backend 设置 `.env` 中的应用密钥、数据库/Redis 连接参数，执行 `./mvnw.cmd -pl backend spring-boot:run`；前端执行 `npm run dev`，Vite 默认将 `/api` 代理至 `localhost:8080`。可用 `compose.dev.yaml` 仅启动并将数据库/Redis端口绑定本机：

```sh
docker compose -f compose.yaml -f compose.dev.yaml up -d postgres redis
```

开发覆盖文件默认使用本机 `15432` 和 `16379`，后端连接分别设置为 `jdbc:postgresql://localhost:15432/clipboard` 和 Redis `localhost:16379`。也可通过 `DEV_POSTGRES_PORT` / `DEV_REDIS_PORT` 修改宿主端口。

已启动整套应用时，Node 22+ 可运行真实 HTTP 冒烟检查：

```sh
node scripts/smoke.mjs
```

脚本验证游客上传、分享下载、七天期限、永久保存、所有权、管理员权限和 JWT 撤销；默认使用本地 `.env` 的管理员凭据清理测试文件。会留下两个 `smoke_` / `other_` 测试用户，请在开发环境执行。指定其他地址使用 `SMOKE_BASE_URL`，管理员凭据可由同名环境变量传入。

更完整的过期、容量与远端迁移检查见 [测试说明](docs/testing.md)。

## 目录

```text
backend/          Spring Boot、鉴权、文件服务、存储适配、Flyway、测试
frontend/         Vue 3、TypeScript、Vite、Nginx
scripts/          密钥初始化与端到端检查
docs/             架构、API 与验证说明
compose.yaml      PostgreSQL、Redis、后端、前端
```

当前版本面向单后端实例部署。本地文件和分片使用独立持久化卷，扩容为多实例时需先引入共享暂存盘与独立迁移任务。后台通过分页与搜索查看全部文件和用户；用户配额和 SSO 为后续扩展。
