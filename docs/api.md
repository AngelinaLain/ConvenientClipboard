# HTTP API

所有路径同源 `/api`，错误格式为 `{ "message": "说明" }`，安全过滤器另外返回 `code`。登录状态由 HttpOnly `clipboard_auth` Cookie 承载。调用写 API 前无缓存 GET `/api/auth/csrf`，保存 `clipboard_csrf` Cookie 并将返回 `token` 放入 `headerName` 指定的请求头。403 `CSRF_INVALID` 可重新获取校验值后重试一次；403 `FORBIDDEN` 及文件所有权拒绝不可作为重试依据。

| 方法 / 路径 | 权限 | 用途 |
| --- | --- | --- |
| GET `/auth/csrf` | 公开 | CSRF token 和头名称 |
| GET `/public/config` | 公开 | 保留天数、单文件大小上限 |
| POST `/auth/register` | 公开 + CSRF | `{username,password}` 注册普通用户并登录 |
| POST `/auth/login` | 公开 + CSRF | `{username,password}` 登录 |
| GET `/auth/me` | 登录 | `{id,username,role}` |
| POST `/auth/logout` | 登录 + CSRF | 撤销当前 JWT |
| POST `/files` | 公开 + CSRF | multipart `file`、`permanent`，永久保存需登录 |
| GET `/files` | 登录 | 自己未过期文件数组 |
| PATCH `/files/{id}/retention` | 所有者 + CSRF | `{permanent:boolean}` |
| DELETE `/files/{id}` | 所有者或管理员 + CSRF | 删除对象和元数据，成功204 |
| GET `/shares/{token}` | 公开 | 共享元数据 |
| GET `/shares/{token}/download` | 公开 | 附件下载 |
| GET `/admin/overview` | 管理员 | 本地/远端字节、文件计数、配置 |
| GET / PUT `/admin/settings` | 管理员，PUT需CSRF | `{localCapacityBytes,offloadThreshold}` |
| GET `/admin/files?page=0&size=20&query=` | 管理员 | 全部文件分页检索，包含 ownerName |
| GET `/admin/users?page=0&size=20&query=` | 管理员 | 用户分页检索、文件数与占用 |
| POST `/admin/users` | 管理员 + CSRF | `{username,password,role}` 创建账号 |
| PATCH `/admin/users/{id}` | 管理员 + CSRF | `{role,enabled}` 修改角色和启用状态 |
| POST `/admin/users/{id}/password` | 管理员 + CSRF | `{password}` 重置密码，成功204 |
| DELETE `/admin/users/{id}` | 管理员 + CSRF | 删除无文件账号，成功204 |
| GET / POST `/admin/providers` | 管理员，POST需CSRF | 列表 / 新增存储器 |
| PUT / DELETE `/admin/providers/{id}` | 管理员 + CSRF | 编辑 / 删除存储器 |
| POST `/admin/maintenance` | 管理员 + CSRF | 提交异步到期清理和转存任务 |

文件元数据包括 `id,originalName,sizeBytes,contentType,permanent,expiresAt,createdAt,location,shareUrl`。`location` 为 `LOCAL`、`S3`、`NFS` 或 `SMB`，日期使用 ISO 8601。文件分页与用户分页返回 `{items,page,size,total}`，页号从0开始、每页1–100条。兼容旧脚本，不带 page/size 的 `/admin/files` 仍返回最新1000条数组；管理页面始终使用分页接口，不受该兼容接口限制。

存储器公共字段：`name,type,enabled,priority`，`type` 为 `S3|NFS|SMB`，省略时兼容旧客户端默认为S3。S3 另传 `endpoint,region,bucket,accessKey,secretKey`；NFS / SMB 另传 `mountPath,mountId`。优先级数值越小越优先。更新时 S3 密钥留空表示不变；查询不返回凭据。挂载路径必须在配置根目录内，不允许符号链接或上级跳转，目录中的 `.clipboard-storage-id` 内容须匹配 `mountId`（1–128位字母、数字、下划线或短横线）。

用户字段：`id,username,role,enabled,createdAt,fileCount,storageBytes`。安全信息变化使旧 JWT 失效；重置自身密码后需要重新登录。自身禁用/降级/删除、最后启用管理员被移除、删除有文件用户均返回409。

用户名为3–32位字母、数字或下划线，统一转为小写；密码8–72个字符且UTF-8不超过72字节。首次管理员密码至少12个字符。本地预算至少1MiB，阈值10–90%。

常见状态：400参数错误、401未登录、403权限/CSRF拒绝、404不存在、409冲突、410分享过期、413上传超限、429认证限流、502远端删除失败、503服务暂不可用、507本地空间不足。
