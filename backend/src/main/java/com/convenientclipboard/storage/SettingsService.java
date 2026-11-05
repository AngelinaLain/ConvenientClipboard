package com.convenientclipboard.storage;

import com.convenientclipboard.common.ApiException;
import com.convenientclipboard.config.AppProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SettingsService {
    private final JdbcTemplate jdbc;
    private final StorageCoordinator coordinator;
    private final LocalStorage local;
    private final AppProperties properties;
    public SettingsService(JdbcTemplate jdbc, StorageCoordinator coordinator, LocalStorage local,AppProperties properties) { this.jdbc=jdbc;this.coordinator=coordinator;this.local=local;this.properties=properties; }
    public StorageSettings get() {
        return jdbc.queryForObject("select local_capacity_bytes,offload_threshold,max_upload_bytes from app_settings where id=1",
            (rs,n)->{
                Long configured=rs.getObject("max_upload_bytes",Long.class);
                long effective=configured==null?properties.maxUploadBytes():configured;
                if(effective<StorageSettings.MIN_UPLOAD_BYTES || effective>StorageSettings.MAX_UPLOAD_BYTES)
                    throw new IllegalStateException("The effective upload limit must be between 1 MiB and 100 GiB; check APP_MAX_UPLOAD_BYTES before first settings initialization");
                return new StorageSettings(rs.getLong("local_capacity_bytes"),rs.getInt("offload_threshold"),effective);
            });
    }
    public StorageSettings save(StorageSettings settings) {
        return coordinator.locked(()->{
            long maximum=settings.maxUploadBytes()==null?get().maxUploadBytes():settings.maxUploadBytes();
            if(maximum<StorageSettings.MIN_UPLOAD_BYTES || maximum>StorageSettings.MAX_UPLOAD_BYTES) throw new ApiException(HttpStatus.BAD_REQUEST,"单文件上传上限必须介于 1 MiB 和 100 GiB 之间");
            if(settings.localCapacityBytes()-local.usedBytes()<coordinator.reservedUploadBytes(null))
                throw new ApiException(HttpStatus.CONFLICT,"容量不能小于本地文件与未完成上传的预留空间，请先转存、删除或取消上传");
            jdbc.update("update app_settings set local_capacity_bytes=?,offload_threshold=?,max_upload_bytes=? where id=1",settings.localCapacityBytes(),settings.offloadThreshold(),maximum);
            return get();
        });
    }
}
