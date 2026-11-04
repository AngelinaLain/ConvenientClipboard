# NFS / SMB 存储接入

应用通过系统已经挂载的共享目录读写文件，NFS/SMB 登录认证由宿主机或 Docker 完成。后台选择类型和挂载路径，无需向应用提供 NAS 用户名或密码。S3 配置方式保持原样。

## 共享目录与标识

backend 容器内允许的根目录为 `/mnt/remote`。例如 NFS 配到 `/mnt/remote/nfs`，SMB 配到 `/mnt/remote/smb`。应用以 UID/GID `10001:10001` 运行，共享目录需允许它创建、读取、重命名和删除文件。

在真实共享目录中预先创建 `.clipboard-storage-id` 文本文件，内容为自己指定的标识，例如 `office-nas-nfs`。标识只使用1–128位字母、数字、下划线或短横线；后台填写同样的值。不要把这个标识写入未挂载时的空占位目录。

标识不存在、内容不匹配、路径越界或包含符号链接时，应用拒绝操作。转存失败会保留本地文件，远端删除失败会保留元数据以便重试。标识不是 NAS 密码，也不能代替系统对实际挂载状态与网络 I/O 的管理。

## 方式一：绑定宿主机的已挂载目录

先在服务器上把共享挂载到同一父目录下，例如 `/srv/clipboard-mounts/nfs` 与 `/srv/clipboard-mounts/smb`。挂载完成并在共享内准备好标识后，在 `.env` 添加：

```dotenv
MOUNTED_STORAGE_HOST_PATH=/srv/clipboard-mounts
```

使用覆盖文件启动：

```sh
docker compose -f compose.yaml -f compose.mounts.yaml up -d --build
```

后台配置例子：

| 类型 | 挂载路径 | 挂载标识 |
| --- | --- | --- |
| NFS | `/mnt/remote/nfs` | `office-nas-nfs` |
| SMB | `/mnt/remote/smb` | `office-nas-smb` |

此覆盖文件要求宿主目录已存在，不会代为创建缺失目录。先完成共享挂载再创建容器；若重新挂载了子目录，需重新创建 backend 以保证容器看到当前挂载。

## 方式二：Docker 卷驱动挂载 NFS

在 `.env` 中添加实际地址和导出路径：

```dotenv
NFS_SERVER=192.0.2.10
NFS_EXPORT=/exports/clipboard
```

```sh
docker compose -f compose.yaml -f compose.nfs.yaml up -d --build
```

后台使用 NFS、路径 `/mnt/remote/nfs`，标识填写共享中预先准备的值。示例采用 NFSv4。NAS需允许 Docker 守护进程所在主机访问，并为UID10001提供相应权限。

## 方式三：Docker 卷驱动挂载 SMB

在忽略版本控制的 `.env` 添加：

```dotenv
SMB_SERVER=192.0.2.20
SMB_SHARE=clipboard
SMB_USERNAME=clipboard-service
SMB_PASSWORD=填写实际密码
```

```sh
docker compose -f compose.yaml -f compose.smb.yaml up -d --build
```

后台使用 SMB、路径 `/mnt/remote/smb`，标识填写共享中的值。示例采用 SMB3。需要同时挂载两种共享时，可以在同一命令中依次加上 `-f compose.nfs.yaml -f compose.smb.yaml`。

卷驱动参数由 Docker 守护进程解释，协议和共享路径需从该主机可达。Docker Desktop 的协议支持取决于其 Linux VM；复杂域认证或凭据中含卷参数分隔符（例如逗号）时，优先使用宿主机挂载后绑定目录的方式。Docker卷驱动挂载失败时，容器不会假装已连接共享。参数说明见 [Docker 官方卷文档](https://docs.docker.com/engine/storage/volumes/)。

## 升级与验证

本次新增 Flyway V2/V3，保留原V1及现有文件。更新代码后执行 `docker compose up -d --build`；配置了共享时继续使用相应覆盖文件。

开发用 `compose.integration.yaml` 通过本地目录模拟已挂载共享，配合 `scripts/admin-mounted-smoke.mjs` 检查文件转存、下载、丢失标识后的拒绝行为、管理员权限与保存期限切换。它验证应用的挂载适配器，不声称连接了真实NAS；真实服务器的协议、导出权限和网络连通性需按以上配置验证。
