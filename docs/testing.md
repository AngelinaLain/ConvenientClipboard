# 验证与存储集成检查

后端测试使用 Java 21 与 Maven Wrapper。前端构建同时执行 TypeScript 类型检查：

```sh
sh ./mvnw verify
cd frontend
npm ci
npm run build
```

Windows 使用 `./mvnw.cmd verify`。后端测试覆盖 JWT、CSRF 与权限边界、七天期限、永久保存与所有权、本地配额、失败上传清理、存储凭据加密，以及远端迁移失败保留本地文件和多提供方回退。实际 HTTP 冒烟脚本还会验证数据库、Redis 和整个 API 链路；单元测试本身不证明部署已成功。

## HTTP 功能检查

在启动后的开发环境运行（Node.js 22+）：

```sh
node scripts/smoke.mjs
```

默认地址 `http://localhost:8088`，可通过 `SMOKE_BASE_URL` 修改。管理员账号与密码默认从本地 `.env` 读取，也可使用 `APP_ADMIN_USERNAME` / `APP_ADMIN_PASSWORD` 环境变量。脚本不会打印凭据。它创建两个测试用户；有管理员凭据时会移除测试文件，测试用户会保留。请使用开发数据库。

## S3 存储集成检查

此检查需要**没有任何文件、且没有已启用远端存储器的独立开发环境**。脚本会暂时把本地预算改为 1 MiB，并创建自己的文件和存储器；结束时会清理其记录并恢复原容量配置。预检不满足时直接停止，避免改动现有文件或存储器。

启动可选的 S3Mock 服务，并保持正常应用运行：

```sh
docker compose -f compose.yaml -f compose.qa.yaml --profile qa up -d s3mock
node scripts/storage-smoke.mjs
```

`compose.qa.yaml` 中的 S3Mock 会预建 `clipboard-tests` 桶。S3Mock 是开发用 S3 协议模拟器，不是生产存储器。默认端点为 `http://s3mock:9090`，这是 **backend 容器访问**的地址。若后端在宿主机运行，应先设置 `SMOKE_S3_ENDPOINT=http://localhost:9090`。客户端应用地址仍由 `SMOKE_BASE_URL` 指定。

脚本依次检查：

1. 未启用远端时，700 KiB 文件先落本地；第二个 700 KiB 文件因 1 MiB 硬配额返回 `507`。
2. 优先存储器不可用时，本地记录及文件保留。
3. 添加健康 S3Mock 存储器后，维护任务回退并完成转存；最多轮询 60 秒。
4. 远端下载逐字节匹配原文件，本地副本在数据库位置提交后移除。
5. 存储器列表不返回访问密钥或加密密文；被文件引用的存储器不能删除。
6. 停用已有存储器后，原文件仍可下载；清理测试对象、存储器，并恢复原设置。

如果清理失败，脚本返回非零退出码，并输出需要处理的测试文件/存储器 ID。恢复远端连接后，删除这些测试对象，再运行维护任务。脚本只操作自己创建的记录。这个脚本不修改数据库时间，因此实际到期物理删除另由后端测试或受控的测试数据库演练验证。

2026-11-04 首版完成以下实际验证：

- Java 21 的 `mvnw verify`：16 项后端测试全部通过。
- Vue TypeScript 检查及 Vite 生产构建通过，前后端生产 Docker 镜像均已构建成功。
- 整套 Docker Compose 启动后，PostgreSQL、Redis、backend、frontend 均为 healthy。
- `http://localhost:8088` 上的功能检查和 S3 检查均通过，包括容器内真实持久化卷、数据库、Redis、S3Mock 转存及下载链路。
- 浏览器实际验证游客上传、复制链接、共享页、用户登录/退出、管理员登录、概览及设置保存。生产页面无浏览器控制台错误。
- 受控测试数据库中将测试文件设为过期，分享元数据及下载立即返回410，定时任务随后删除记录及本地对象。

还在宿主机 Java 21 后端与 Docker 依赖的组合中运行过相同检查。验证结束后，临时测试文件、提供方、测试账号和 S3Mock 服务均已清理；本地预算恢复为10GiB、阈值50%。正式的四个 Compose 服务保留运行。执行检查脚本时通常仍会留下测试账号，本次人工验证额外清理了这些账号。

## NFS / SMB、用户管理与 CSRF 回归

本次更新另外完成：Linux Docker 中34项后端测试全部通过，0跳过；Windows上33项通过、符号链接用例因系统权限跳过；前端11项回归测试、TypeScript检查与生产构建通过。

独立QA环境使用自己的数据库、Redis和文件卷，端口18088。创建本地挂载模拟目录与标识后，使用以下命令启动（`.local/qa.env` 必须包含独立随机密钥、数据库密码、管理员密码，并设置 `APP_PORT=18088`）：

```sh
docker compose build backend frontend
docker compose -p convenient-clipboard-qa --env-file .local/qa.env -f compose.yaml -f compose.integration.yaml up -d --no-build --wait
node scripts/admin-mounted-smoke.mjs
```

标识文件：`.local/qa-mounts/nfs/.clipboard-storage-id` 内容为 `qa-nfs-share`；SMB目录对应 `qa-smb-share`。脚本默认连接18088并读取 `.local/qa.env`，也可通过 `SMOKE_BASE_URL` 与 `SMOKE_ENV_FILE` 指定；它只接受没有用户文件且只有初始化管理员的QA环境，避免转存真实数据。

实际通过的集成检查包括用户分页与角色权限、停用/重新启用及重置密码后的旧令牌失效、管理员删除其他用户/游客文件、全部文件分页、过期CSRF明确拒绝后使用新值重试、NFS与SMB挂载目录转存和逐字节下载、挂载标识消失时拒绝下载/删除且保留元数据、测试对象清理与原容量设置恢复。原有HTTP与S3集成脚本在同一独立环境也已通过。

浏览器连续四次切换临时/永久保存，无刷新且无报错；另外检查了用户管理、全站文件列表及NFS/SMB配置表单。真实NAS的协议连接未测试，因为未提供服务器地址与凭据；当前验证覆盖挂载文件系统适配器，生产挂载按 [配置说明](storage-mounts.md) 完成。

现有部署已通过V2/V3升级与四个服务的健康检查；升级前后原有文件的SHA-256核对一致。隔离测试容器及其专用数据卷在验证后清理，生产数据卷保留。
