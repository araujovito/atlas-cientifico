#!/usr/bin/env bash
# Cópia de segurança do Atlas: banco (pg_dump) e documentos (tar), com SHA256SUMS e retenção.
#   ATLAS_BACKUP_DIR=/caminho/backups ./deploy/backup.sh
# Variáveis: ATLAS_PROJETO (projeto do compose, padrão "atlas"), ATLAS_BACKUP_MANTER (cópias mantidas, padrão 7),
#            ATLAS_BACKUP_GPG_DESTINATARIO (chave PÚBLICA do keyring do GnuPG; cifra o banco e os documentos
#            em fluxo, sem gravar versão em claro. A chave privada fica com você, fora do servidor).
set -euo pipefail
umask 077
cd "$(dirname "$0")"

destino="${ATLAS_BACKUP_DIR:?Defina ATLAS_BACKUP_DIR}"
projeto="${ATLAS_PROJETO:-atlas}"
manter="${ATLAS_BACKUP_MANTER:-7}"
[[ "$manter" =~ ^[1-9][0-9]*$ ]] || { echo "ATLAS_BACKUP_MANTER deve ser um inteiro positivo." >&2; exit 2; }
compose() { docker compose -p "$projeto" -f compose.yml "$@"; }
destinatario="${ATLAS_BACKUP_GPG_DESTINATARIO:-}"
sufixo=""
cifrar() { if [[ -n "$destinatario" ]]; then gpg --batch --yes --trust-model always --encrypt --recipient "$destinatario"; else cat; fi; }
[[ -z "$destinatario" ]] || sufixo=".gpg"

carimbo="$(date -u +%Y%m%dT%H%M%SZ)"
pasta="$destino/$carimbo"
mkdir -p "$pasta.parcial"
trap 'rm -rf "$pasta.parcial"' ERR

# 1) O banco primeiro. Os PDFs são gravados no disco ANTES da linha no banco e nunca mudam (nome = hash);
#    copiar os arquivos depois do dump garante que todo documento citado no dump está na cópia.
#    sessao e limite_tentativa ficam sem dados: um backup não deve ressuscitar sessões abertas.
compose exec -T db pg_dump -U atlas -d atlas -Fc \
  --exclude-table-data=sessao --exclude-table-data=limite_tentativa | cifrar > "$pasta.parcial/banco.dump$sufixo"

# 2) Os documentos, lidos do volume em modo somente leitura.
docker run --rm -v "${projeto}_documentos:/dados:ro" --entrypoint tar postgres:18 -C /dados -cf - . | cifrar > "$pasta.parcial/documentos.tar$sufixo"

(cd "$pasta.parcial" && sha256sum "banco.dump$sufixo" "documentos.tar$sufixo" > SHA256SUMS)
mv "$pasta.parcial" "$pasta"
trap - ERR
echo "Cópia criada em $pasta"

# 3) Retenção: só pastas com nome de carimbo, as mais antigas primeiro. Nunca remove fora de ATLAS_BACKUP_DIR.
mapfile -t antigas < <(find "$destino" -mindepth 1 -maxdepth 1 -type d -name '[0-9]*T[0-9]*Z' | sort | head -n -"$manter")
for velha in "${antigas[@]}"; do rm -rf -- "$velha"; echo "Removida cópia antiga $velha"; done
