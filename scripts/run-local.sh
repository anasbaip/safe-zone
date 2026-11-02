#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$project_root"
for executable in docker openssl java npm curl; do
  command -v "$executable" >/dev/null || { echo "Required command missing: $executable" >&2; exit 1; }
done
docker info >/dev/null
umask 077
mkdir -p .local/certs

if [[ ! -f .local/app.env ]]; then
  cat > .local/app.env <<EOF
APP_IMAGE_TAG=buy02-local
GATEWAY_PORT=8444
JWT_SECRET=$(openssl rand -hex 32)
JWT_EXPIRATION=86400000
SERVER_SSL_KEY_STORE_PASSWORD=$(openssl rand -hex 24)
MONGO_ROOT_USERNAME=local_admin
MONGO_ROOT_PASSWORD=$(openssl rand -hex 24)
PRODUCT_MONGO_DATABASE=product_service_db
PRODUCT_MONGO_USERNAME=product_service
PRODUCT_MONGO_PASSWORD=$(openssl rand -hex 24)
MEDIA_MONGO_DATABASE=media_service_db
MEDIA_MONGO_USERNAME=media_service
MEDIA_MONGO_PASSWORD=$(openssl rand -hex 24)
USER_MONGO_DATABASE=user_service_db
USER_MONGO_USERNAME=user_service
USER_MONGO_PASSWORD=$(openssl rand -hex 24)
ORDER_MONGO_DATABASE=order_service_db
ORDER_MONGO_USERNAME=order_service
ORDER_MONGO_PASSWORD=$(openssl rand -hex 24)
CLOUDINARY_CLOUD_NAME=not-configured
CLOUDINARY_API_KEY=not-configured
CLOUDINARY_API_SECRET=not-configured
EOF
  echo 'Created private development configuration in .local/app.env.'
fi

if [[ ! -f .local/certs/gateway-keystore.p12 ]]; then
  openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 365 \
    -keyout .local/certs/gateway-keystore.key -out .local/certs/gateway-keystore.crt \
    -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost,DNS:gateway,IP:127.0.0.1' 2>/dev/null
  # Read only the generated keystore password; do not source configuration as shell code.
  local_keystore_password=$(sed -n 's/^SERVER_SSL_KEY_STORE_PASSWORD=//p' .local/app.env)
  export local_keystore_password
  openssl pkcs12 -export -name gateway \
    -inkey .local/certs/gateway-keystore.key -in .local/certs/gateway-keystore.crt \
    -out .local/certs/gateway-keystore.p12 -passout env:local_keystore_password
  unset local_keystore_password
fi

compose=(docker compose --env-file .local/app.env -p buy02-local -f docker-compose.yml -f compose.local.yml)
"${compose[@]}" config --quiet
if [[ "${1:-}" != '--skip-build' ]]; then
  for service in product-service user-service media-service order-service gateway discovery; do
    (cd "$service" && bash ./mvnw -B package -DskipTests)
  done
  (cd frontend && if [[ ! -d node_modules ]]; then npm ci; fi && npm run build)
fi
"${compose[@]}" up -d --build --wait --wait-timeout 600
# Eureka registration and gateway registry caches can lag container health checks.
routes_ready=false
for ((attempt = 1; attempt <= 30; attempt++)); do
  catalog_status=$(curl -ksS --max-time 3 -o /dev/null -w '%{http_code}' https://localhost:4200/api/products/search || true)
  orders_status=$(curl -ksS --max-time 3 -o /dev/null -w '%{http_code}' https://localhost:4200/api/orders/my || true)
  if [[ "$catalog_status" == 200 && "$orders_status" == 401 ]]; then
    routes_ready=true
    break
  fi
  sleep 3
done
if [[ "$routes_ready" != true ]]; then
  echo 'Containers started, but API routes are not ready. Inspect local service logs.' >&2
  exit 1
fi
echo 'Marketplace: https://localhost:4200 (accept the local development certificate).'
echo 'Configure CLOUDINARY_* in .local/app.env and rerun this script before testing image uploads.'
