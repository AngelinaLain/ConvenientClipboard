$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot '.env'
if (Test-Path -LiteralPath $envPath) { throw '.env already exists. Keep the existing storage encryption key and database passwords.' }
function New-Secret {
    $secretBytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($secretBytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($secretBytes)
}
$envLines = @(
    'APP_PORT=8088',
    'APP_BIND_ADDRESS=127.0.0.1',
    'APP_PUBLIC_BASE_URL=http://localhost:8088',
    'APP_ADMIN_USERNAME=admin',
    ('APP_ADMIN_PASSWORD=' + (New-Secret)),
    ('APP_JWT_SECRET=' + (New-Secret)),
    ('APP_STORAGE_ENCRYPTION_KEY=' + (New-Secret)),
    ('POSTGRES_PASSWORD=' + (New-Secret)),
    ('REDIS_PASSWORD=' + (New-Secret)),
    'APP_COOKIE_SECURE=false',
    'APP_MAX_UPLOAD_BYTES=1073741824'
)
[System.IO.File]::WriteAllLines($envPath, $envLines, (New-Object System.Text.UTF8Encoding($false)))
Write-Output 'Created .env with unique secrets. The administrator password is APP_ADMIN_PASSWORD in .env.'
