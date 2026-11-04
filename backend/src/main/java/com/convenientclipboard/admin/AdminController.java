package com.convenientclipboard.admin;

import com.convenientclipboard.files.FileInfo;
import com.convenientclipboard.files.FileRepository;
import com.convenientclipboard.files.FileService;
import com.convenientclipboard.files.StoredFile;
import com.convenientclipboard.common.ApiException;
import com.convenientclipboard.storage.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final FileRepository files;
    private final FileService fileService;
    private final LocalStorage local;
    private final SettingsService settings;
    private final ProviderService providers;
    private final StorageMaintenance maintenance;
    public AdminController(FileRepository files,FileService fileService,LocalStorage local,SettingsService settings,ProviderService providers,StorageMaintenance maintenance) { this.files=files;this.fileService=fileService;this.local=local;this.settings=settings;this.providers=providers;this.maintenance=maintenance; }
    @GetMapping("/overview") public Map<String,Object> overview() {
        StorageSettings limits=settings.get();
        return Map.of("localUsedBytes",local.usedBytes(),"remoteUsedBytes",files.remoteBytes(),"totalFiles",files.total(),"permanentFiles",files.permanentCount(),"localCapacityBytes",limits.localCapacityBytes(),"offloadThreshold",limits.offloadThreshold(),"enabledProviders",providers.enabled().size(),"maintenanceRunning",maintenance.running());
    }
    @GetMapping("/settings") public StorageSettings settings() { return settings.get(); }
    @PutMapping("/settings") public StorageSettings settings(@Valid @RequestBody StorageSettings request) { StorageSettings saved=settings.save(request);maintenance.request();return saved; }
    @GetMapping("/files") public Object files(@RequestParam(required=false) Integer page,@RequestParam(required=false) Integer size,@RequestParam(defaultValue="") String query) {
        if(page==null && size==null && query.isBlank()) return files.all().stream().map(file->fileRow(file,files.ownerName(file.ownerId()))).toList();
        int requestedPage=page==null?0:page;
        int requestedSize=size==null?20:size;
        if(requestedPage<0 || requestedSize<1 || requestedSize>100 || query.length()>200) throw new ApiException(HttpStatus.BAD_REQUEST,"页码需从 0 开始，每页 1–100 条，搜索文本最多 200 字");
        String search=query.strip();
        List<Map<String,Object>> items=files.adminPage(requestedPage,requestedSize,search).stream().map(entry->fileRow(entry.file(),entry.ownerName())).toList();
        return Map.of("items",items,"page",requestedPage,"size",requestedSize,"total",files.adminCount(search));
    }
    private Map<String,Object> fileRow(StoredFile file,String ownerName) {
        FileInfo info=fileService.info(file);
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("id",info.id());row.put("originalName",info.originalName());row.put("sizeBytes",info.sizeBytes());row.put("contentType",info.contentType());
        row.put("permanent",info.permanent());row.put("expiresAt",info.expiresAt());row.put("createdAt",info.createdAt());row.put("location",info.location());row.put("shareUrl",info.shareUrl());row.put("ownerName",ownerName);
        return row;
    }
    @GetMapping("/providers") public List<ProviderService.ProviderInfo> providers() { return providers.list(); }
    @PostMapping("/providers") public ProviderService.ProviderInfo create(@Valid @RequestBody ProviderRequest request) { var saved=providers.save(null,request);maintenance.request();return saved; }
    @PutMapping("/providers/{id}") public ProviderService.ProviderInfo update(@PathVariable UUID id,@Valid @RequestBody ProviderRequest request) { var saved=providers.save(id,request);maintenance.request();return saved; }
    @DeleteMapping("/providers/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id) { providers.delete(id);return ResponseEntity.noContent().build(); }
    @PostMapping("/maintenance") public Map<String,String> maintenance() { maintenance.request();return Map.of("message","已安排清理与转存任务，失败的文件将保留并在后续重试"); }
}
