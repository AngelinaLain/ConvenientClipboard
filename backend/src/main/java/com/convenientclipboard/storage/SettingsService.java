package com.convenientclipboard.storage;

import com.convenientclipboard.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SettingsService {
    private final JdbcTemplate jdbc;
    private final StorageCoordinator coordinator;
    private final LocalStorage local;
    public SettingsService(JdbcTemplate jdbc, StorageCoordinator coordinator, LocalStorage local) { this.jdbc=jdbc;this.coordinator=coordinator;this.local=local; }
    public StorageSettings get() {
        return jdbc.queryForObject("select local_capacity_bytes,offload_threshold from app_settings where id=1",
            (rs,n)->new StorageSettings(rs.getLong(1),rs.getInt(2)));
    }
    public StorageSettings save(StorageSettings settings) {
        return coordinator.locked(()->{
            if(settings.localCapacityBytes()<local.usedBytes()) throw new ApiException(HttpStatus.CONFLICT,"容量不能小于当前本地文件占用，请先转存或删除文件");
            jdbc.update("update app_settings set local_capacity_bytes=?,offload_threshold=? where id=1",settings.localCapacityBytes(),settings.offloadThreshold());
            return get();
        });
    }
}
