#!/usr/bin/env bash
# Valida o overlay do proxy sem subir nada: o compose resolve, o Caddyfile é aceito pelo Caddy e as
# variáveis de segurança chegam à API. Não prova emissão de certificado nem tráfego real.
set -euo pipefail
cd "$(dirname "$0")"
export ATLAS_DB_PASSWORD=x ATLAS_CHAVE_API=x ATLAS_DOMINIO=atlas.exemplo.test
config="$(docker compose -f compose.yml -f compose.proxy.yml config)"
grep -q 'ATLAS_ORIGENS_PERMITIDAS: https://atlas.exemplo.test' <<<"$config" || { echo "FALHOU: origem do proxy ausente" >&2; exit 1; }
grep -q 'ATLAS_LIMITES_CABECALHO_IP: X-Forwarded-For' <<<"$config" || { echo "FALHOU: cabeçalho de IP ausente" >&2; exit 1; }
# A API não pode ficar publicada fora de 127.0.0.1.
if grep -E 'published: "?(8080|8081)' -B2 <<<"$config" | grep -q 'host_ip: 0.0.0.0'; then echo "FALHOU: API exposta" >&2; exit 1; fi
imagem="$(grep -m1 'image: caddy' compose.proxy.yml | sed 's/.*image: //')"
docker run --rm -e ATLAS_DOMINIO -v "$PWD/Caddyfile:/etc/caddy/Caddyfile:ro" "$imagem" caddy validate --config /etc/caddy/Caddyfile >/dev/null 2>&1 \
  || { echo "FALHOU: Caddyfile inválido" >&2; exit 1; }
# Sem domínio o compose deve recusar subir.
if env -u ATLAS_DOMINIO docker compose -f compose.yml -f compose.proxy.yml config >/dev/null 2>&1; then echo "FALHOU: aceitou subir sem ATLAS_DOMINIO" >&2; exit 1; fi
echo "OK: overlay do proxy e Caddyfile validados."
