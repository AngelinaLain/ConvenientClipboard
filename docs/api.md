# HTTP API

所有路径同源 `/api`，错误格式为 `{ "message": "说明" }`，安全过滤器另外返回 `code`。登录状态由 HttpOnly `clipboard_auth` Cookie 承载。调用写 API 前无缓存 GET `/api/auth/csrf`，保存 `clipboard_csrf` Cookie 并将返回 `token` 放入 `headerName` 指定的请求头。403 `CSRF_INVALID` 可重新获取校验值后重试一次；403 `FORBIDDEN` 及文件所有权拒绝不可作为重试依据。

| 方法 / 路径 | 权限 | 用途 |
| --- | --- | --- |
| GET `/auth/csrf` | 公开 | CSRF token 和头名称 |
| GET `/public/config` | 公开 | 保留天数、单文件大小上限、匿名上传及注册开关 |
| POST `/auth/register` | 开放注册时公开 + CSRF | `{username,password}` 注册普通用户并登录 |
| POST `/auth/login` | 公开 + CSRF | `{username,password}` 登录 |
| GET `/auth/me` | 登录 | `{id,username,role}` |
| POST `/auth/logout` | 登录 + CSRF | 撤销当前 JWT |
| POST `/files` | 登录或允许匿名上传 + CSRF | multipart `file`、`permanent`，永久保存需登录 |
| POST `/uploads` | 上传令牌 + CSRF，受匿名上传开关约束 | 创建或恢复分片上传任务，同ID同元数据幂等 |
| GET `/uploads/{id}` | 上传令牌，账号任务还需原账号 | 查询服务端已接收分片和完成结果 |
| PUT `/uploads/{id}/chunks/{index}` | 上传令牌 + CSRF，受匿名上传开关约束 | 原始二进制分片，校验大小和SHA-256 |
| POST `/uploads/{id}/complete` | 上传令牌 + CSRF，未完成匿名任务受开关约束 | 流式合并并提交文件，重复调用返回同一结果 |
| DELETE `/uploads/{id}` | 上传令牌 + CSRF | 取消未完成任务并释放容量预留 |
| GET `/files` | 登录 | 自己未过期文件数组 |
| PATCH `/files/{id}/retention` | 所有者 + CSRF | `{permanent:boolean}` |
| DELETE `/files/{id}` | 所有者或管理员 + CSRF | 删除对象和元数据，成功204 |
| GET `/shares/{token}` | 公开 | 共享元数据 |
| GET `/shares/{token}/download` | 公开 | 附件下载 |
| POST `/clipboards` | 公开 + CSRF | `{content,customId?}` 保存纯文本，可选自定义链接，返回201和固定10分钟分享信息 |
| GET `/clipboards/{id}` | 公开 | 读取未过期的文本及分享信息，不续期 |
| GET `/admin/overview` | 管理员 | 本地/远端字节、文件计数、配置 |
| GET / PUT `/admin/settings` | 管理员，PUT需CSRF | `{localCapacityBytes,offloadThreshold,maxUploadBytes,allowAnonymousUploads,registrationEnabled}` |
| GET `/admin/files?page=0&size=20&query=&location=&retention=` | 管理员 | 全部文件分页、关键词检索与组合筛选，包含 ownerName |
| GET `/admin/users?page=0&size=20&query=` | 管理员 | 用户分页检索、文件数与占用 |
| POST `/admin/users` | 管理员 + CSRF | `{username,password,role}` 创建账号 |
| PATCH `/admin/users/{id}` | 管理员 + CSRF | `{role,enabled}` 修改角色和启用状态 |
| POST `/admin/users/{id}/password` | 管理员 + CSRF | `{password}` 重置密码，成功204 |
| DELETE `/admin/users/{id}` | 管理员 + CSRF | 删除无文件账号，成功204 |
| GET / POST `/admin/providers` | 管理员，POST需CSRF | 列表 / 新增存储器 |
| PUT / DELETE `/admin/providers/{id}` | 管理员 + CSRF | 编辑 / 删除存储器 |
| POST `/admin/providers/{id}/speed-test` | 管理员 + CSRF | 启动异步双向测速，无请求体，成功202 |
| GET `/admin/providers/{id}/speed-test/{testId}` | 管理员 | 查询测速阶段、速率、耗时与清理警告 |
| POST `/admin/maintenance` | 管理员 + CSRF | 提交异步到期清理和转存任务 |

文件元数据包括 `id,originalName,sizeBytes,contentType,permanent,expiresAt,createdAt,location,shareUrl`。`location` 为 `LOCAL`、`S3`、`NFS` 或 `SMB`，日期使用 ISO 8601。文件分页与用户分页返回 `{items,page,size,total}`，页号从0开始、每页1–100条。兼容旧脚本，不带分页、关键词和筛选条件的 `/admin/files` 仍返回最新1000条数组；管理页面始终使用分页接口，不受该兼容接口限制。

管理文件列表的 `query` 最多200字，忽略首尾空白，按文件名、上传用户名（游客为“匿名用户”）或文件ID进行不区分大小写的包含匹配，`%`、`_` 等按普通字符匹配。`location` 可为 `LOCAL|S3|NFS|SMB`，`retention` 可为 `PERMANENT|TEMPORARY`，空值表示全部；临时保存包含尚未清理的过期文件。关键词和两个筛选条件可组合使用，`total` 返回相同条件下的总数，按上传时间倒序排列。非法筛选值返回400。只传检索/筛选条件时默认第0页、每页20条。

后台文件页提供关键词搜索与重置、存储位置和保存方式筛选，每页10/20/50/100条、首尾页与页码跳转。提交新搜索或改变每页条数时回到第一页，翻页和刷新沿用已提交的条件；删除末页最后一个文件后自动回到有效页。

存储器公共字段：`name,type,enabled,priority`，`type` 为 `S3|NFS|SMB`，省略时兼容旧客户端默认为S3。S3 另传 `endpoint,region,bucket,accessKey,secretKey`；NFS / SMB 另传 `mountPath,mountId`。优先级数值越小越优先。更新时 S3 密钥留空表示不变；查询不返回凭据。挂载路径必须在配置根目录内，不允许符号链接或上级跳转，目录中的 `.clipboard-storage-id` 内容须匹配 `mountId`（1–128位字母、数字、下划线或短横线）。

测速支持全部三种存储类型和已停用的配置，上传方向是服务器→远端，下载方向是远端→服务器。POST返回任务快照，GET使用相同格式：

```text
{ id, providerId, status, sampleBytes: 16777216,
  uploadBytesPerSecond, downloadBytesPerSecond,
  uploadDurationMillis, downloadDurationMillis,
  startedAt, finishedAt, error, warning }
```

`status` 依次为 `PREPARING|UPLOADING|DOWNLOADING|CLEANING`，终态为 `COMPLETED|FAILED`。未得到的速率、耗时及未发生的错误/警告为`null`，时间为ISO 8601。可每秒GET查询；传输失败保留已完成方向的结果，并在清理后发布终态。上传包括现有存储适配器的连接和大小校验，下载包括流式读取与SHA-256处理；NFS/SMB可能命中系统缓存。16MiB随机样本生成与最终清理不计入传输耗时，不创建文件元数据。

全局同时只运行一项测速，忙时POST返回409。创建请求不会幂等合并，客户端收到普通网络错误时不自动重复POST；GET暂时失败可使用原ID重读。任务和最近20项记录仅保存在当前后端进程内，服务重启、记录被淘汰或存储器ID不匹配时GET返回404。使用开始时的存储配置清理测试对象；失败时`warning`包含需要手动清理的远端对象UUID或服务器`speed-tests`临时文件名，连接异常详细信息及凭据不会回传。

用户字段：`id,username,role,enabled,createdAt,fileCount,storageBytes`。安全信息变化使旧 JWT 失效；重置自身密码后需要重新登录。自身禁用/降级/删除、最后启用管理员被移除、删除有文件用户均返回409。

用户名为3–32位字母、数字或下划线，统一转为小写；密码8–72个字符且UTF-8不超过72字节。首次管理员密码至少12个字符。本地预算至少1MiB，阈值10–90%。

`maxUploadBytes` 是单文件上限，范围 1048576–107374182400（1 MiB–100 GiB），保存后持久化并立即用于后续上传，无需重启；省略或传 `null` 保留当前值，旧版容量设置客户端仍可使用。GET `/public/config` 的 `maxUploadBytes` 返回当前有效上限，前台在进入上传页、重新聚焦及开始上传批次时更新。限制同时覆盖 Servlet multipart 解析和最终文件写入；超限返回413，实际磁盘或本地预算不足返回507。降低上限不影响已存文件的分享、下载和保存期限。

`allowAnonymousUploads` 与 `registrationEnabled` 为布尔值，默认均为 `true`，通过 `/admin/settings` 保存到数据库并立即生效；旧客户端省略这些字段时保留当前值。GET `/public/config` 同时返回这两个当前值，供上传页与登录页显示对应入口。关闭匿名上传后，游客不能使用旧整文件接口或新建分片任务；已有匿名任务也不能续租、写片或提交完成，即使持令牌的人后来登录也不能改变该任务的匿名身份，但仍可查询原结果或取消未完成任务。关闭注册后，POST `/auth/register` 返回403，已有用户登录和管理员创建账号不受影响。开关不影响已保存文件的分享与下载。

常见状态：400参数错误、401未登录、403权限/CSRF拒绝、404不存在、409冲突、410分享过期、413上传超限、429认证限流、502远端删除失败、503服务暂不可用、507本地空间不足。

## 临时粘贴板

POST `/clipboards` 请求为 `{ "content": "需要分享的文本" }`。文本不能为空或纯空白，最多100000个UTF-16代码单元（与浏览器的`maxlength`计数一致），原样保留空格、换行和特殊字符。JSON请求体另有1MiB上限，超限返回413，包括无Content-Length的请求。创建与读取均无需登录，与文件匿名上传、开放注册设置无关；创建仍使用上文的CSRF防护。

创建和读取均返回 `{id,content,shareUrl,createdAt,expiresAt}`，日期为ISO 8601。`shareUrl` 使用 `APP_PUBLIC_BASE_URL`，页面路径为 `/zt/{id}`，编辑页为 `/zt`。旧 `/clipboard` 和 `/clipboard/{id}` 链接兼容跳转到对应短路径；已有Redis记录在读取时也返回 `/zt/{id}` 分享地址，不修改其内容或有效期。服务端固定 `expiresAt = createdAt + 10分钟`，不接受自定义有效期或永久保存。Redis保存文本并设置自动过期，读取不会续期；到期或不存在返回404。文本不写入文件存储或PostgreSQL，不计入文件容量和管理列表。

可选字段 `customId` 指定链接名称，例如 `{ "content": "会议记录", "customId": "team-note" }` 生成 `/zt/team-note`。名称忽略首尾空白，区分大小写，长度3–64位，以英文字母或数字开头，其余允许英文字母、数字、下划线和短横线。省略、`null`或纯空白时生成随机链接，非法名称返回400。同名链接在原10分钟有效期内再次创建返回409并提示换一个名称，旧内容和期限保持不变；Redis原子占用防止并发覆盖，到期后名称可再次使用。自定义名称与随机链接共用同一命名空间，已有随机链接继续有效。

接口响应使用`Cache-Control: no-store`。页面只展示纯文本，可复制内容和分享链接；到期后清空已展示的内容及链接。已被访客主动复制或保存到其他位置的副本不受服务端期限控制。

## 分片上传协议

客户端先按8MiB切分文件，逐片计算小写十六进制SHA-256。创建前生成随机UUID任务ID和32字节安全随机上传令牌（编码为64位小写hex），将ID、令牌、文件元数据及完整分片校验列表持久化；创建响应丢失时仍使用原ID重试。所有任务请求均在 `X-Upload-Token` 头中携带该令牌，服务端仅保存其哈希，不在响应中回传。账号创建的任务还要求原账号登录；游客任务使用同一令牌恢复。写操作仍须满足常规CSRF校验。

POST `/uploads` 请求：

```json
{
  "id": "客户端生成的UUID",
  "originalName": "example.bin",
  "contentType": "application/octet-stream",
  "sizeBytes": 8388611,
  "permanent": false,
  "chunkHashes": ["第0片的64位SHA-256", "最后3字节的64位SHA-256"]
}
```

文件必须非空，分片数量严格等于 `ceil(sizeBytes / 8388608)`。创建JSON有2MiB请求体上限；不允许借省略Content-Length绕过。创建时验证当前单文件上限并预留完整文件的本地预算。同任务ID、同令牌、同元数据和校验列表重复提交会返回原任务，改变元数据返回409；令牌或所有者不匹配返回404。

创建、查询、成功写片与完成接口返回：

```text
{ id, originalName, contentType, sizeBytes, permanent,
  chunkSize: 8388608, totalChunks, receivedChunks: [0, 2, ...],
  uploadedBytes, state: "UPLOADING" | "COMPLETED", expiresAt, file }
```

`uploadedBytes` 只计算服务端已确认的完整分片。`file` 在完成后返回现有文件元数据及原分享链接。索引从0开始；PUT `/uploads/{id}/chunks/{index}` 的Content-Type为 `application/octet-stream`，请求体只能是对应分片字节。非末片必须为8MiB，末片长度由文件总大小决定。短片400、超长413、哈希不符422，均不会被记为已完成分片。同一分片重传会重新校验内容，不会重复计数。

恢复时先GET任务，保留 `receivedChunks`，只PUT缺少的索引。全部分片确认后调用POST `/complete`；合并和结果提交使用任务锁与数据库事务保护，并发或响应丢失后重试只产生一个文件和一个分享链接。完整文件仍在提交时复核最新单文件上限和可用容量；降低上限导致413时，已收分片保留，可恢复设置后继续。缺片时完成返回409。文件之后被删除，也不会因为重试完成接口而重新创建。

DELETE只取消未完成任务，已完成返回409，可GET取回原结果。取消和过期任务返回410；保留7天取消/过期记录防止迟到的创建或重试重新激活任务。完成任务至少保留7天幂等结果。未完成任务10分钟无有效写入后失效，单纯查询不续期；成功写片或幂等创建会将活动期限更新为当前时间加10分钟。`expiresAt` 是服务端权威截止时间，并非文件上传总时长限制。超过截止时间立即不再占用预留容量；物理分片由默认每30秒运行的维护任务回收，清理失败后重试。

用户主动继续任务时，会用同ID的幂等创建请求续租；重新选择原文件后的长时间内容校验期间，每分钟显式续租，暂停或离开页面后停止。服务端接收和合并期间有字节进展时也会更新租期，活动截止时间通过独立短事务提交，其他容量检查能够及时看见；过期任务不能被续租复活。普通GET轮询不延长任务寿命。

前端任务清单及服务端截止时间保存在当前站点的localStorage，源文件尽力缓存在IndexedDB。恢复先查询服务端，再读取原文件缓存；缓存成功时刷新可自动恢复，缓存不足或被浏览器回收时，需要重新选择原文件，并逐片复核校验列表后续传。收到410或已确认任务的404后，前端清理对应任务记录与源文件缓存；没有收到创建确认的任务超过10分钟也先查询，404才清理，不会重新创建过期任务。跨标签页活动可能更新截止时间，因此不会仅凭本地旧时间删除仍有效的服务端任务。任务按原账号隔离，暂停只中止传输，取消才通知服务器释放任务；清除浏览器站点数据会丢失本地令牌。
