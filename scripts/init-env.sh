#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
if [ -e .env ]; then
  echo '.env already exists. Keep the existing storage encryption key and database passwords.' >&2
  exit 1
fi
command -v openssl >/dev/null 2>&1 || { echo 'openssl is required' >&2; exit 1; }
umask 077
{
  printf 'APP_PORT=8088\nAPP_BIND_ADDRESS=127.0.0.1\nAPP_PUBLIC_BASE_URL=http://localhost:8088\nAPP_ADMIN_USERNAME=admin\n'
  for key in APP_ADMIN_PASSWORD APP_JWT_SECRET APP_STORAGE_ENCRYPTION_KEY POSTGRES_PASSWORD REDIS_PASSWORD; do
    printf '%s=%s\n' "$key" "$(openssl rand -base64 32)"
  done
  printf 'APP_COOKIE_SECURE=false\nAPP_MAX_UPLOAD_BYTES=1073741824\n'
} > .env
echo 'Created .env with unique secrets. The administrator password is APP_ADMIN_PASSWORD in .env.'
