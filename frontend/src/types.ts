export interface User {
    id: string;
    username: string;
    role: 'ADMIN' | 'USER'
}

export interface FileInfo {
    id: string;
    originalName: string;
    sizeBytes: number;
    contentType: string;
    permanent: boolean;
    expiresAt: string | null;
    createdAt: string;
    location: 'LOCAL' | 'S3' | 'NFS' | 'SMB';
    shareUrl: string;
    ownerName?: string
}

export interface PublicConfig {
    retentionDays: number;
    maxUploadBytes: number;
    registrationEnabled: boolean
}

export interface Overview {
    localUsedBytes: number;
    remoteUsedBytes: number;
    totalFiles: number;
    permanentFiles: number;
    localCapacityBytes: number;
    offloadThreshold: number;
    enabledProviders: number
}

export interface Settings {
    localCapacityBytes: number;
    offloadThreshold: number;
    maxUploadBytes: number
}

export interface Provider {
    id: string;
    name: string;
    type: 'S3' | 'NFS' | 'SMB';
    endpoint: string;
    region: string;
    bucket: string;
    mountPath: string;
    mountId: string;
    enabled: boolean;
    priority: number;
    hasCredentials?: boolean
}

export interface StorageSpeedTest {
    id: string;
    providerId: string;
    status: 'PREPARING' | 'UPLOADING' | 'DOWNLOADING' | 'CLEANING' | 'COMPLETED' | 'FAILED';
    sampleBytes: number;
    uploadBytesPerSecond: number | null;
    downloadBytesPerSecond: number | null;
    uploadDurationMillis: number | null;
    downloadDurationMillis: number | null;
    startedAt: string;
    finishedAt: string | null;
    error: string | null;
    warning: string | null
}

export interface Page<T> {
    items: T[];
    page: number;
    size: number;
    total: number
}

export interface AdminUser extends User {
    enabled: boolean;
    createdAt: string;
    fileCount: number;
    storageBytes: number
}
