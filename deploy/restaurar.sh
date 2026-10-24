#!/usr/bin/env bash
# Restaura uma cópia em um banco NOVO e em uma pasta de documentos VAZIA. Não sobrescreve o banco em uso:
# depois de conferir, aponte ATLAS_DB_URL e ATLAS_DOCUMENTOS_DIR para o que foi restaurado.
#   ATLAS_RESTAURAR_DOCUMENTOS=/pasta/vazia ./deploy/restaurar.sh /caminho/backups/20261024T120000Z
# Variáveis: ATLAS_PROJETO (padrão "atlas"), ATLAS_RESTAURAR_BANCO (padrão "atlas_restaurado").
set -euo pipefail
umask 077
cd "$(dirname "$0")"

origem="${1:?Informe a pasta da cópia}"
projeto="${ATLAS_PROJETO:-atlas}"
banco="${ATLAS_RESTAURAR_BANCO:-atlas_restaurado}"
documentos="${ATLAS_RESTAURAR_DOCUMENTOS:?Defina ATLAS_RESTAURAR_DOCUMENTOS (pasta vazia)}"
[[ "$banco" =~ ^[a-z_][a-z0-9_]*$ ]] || { echo "Nome de banco inválido." >&2; exit 2; }
[[ "$banco" != "atlas" ]] || { echo "Recuso restaurar sobre o banco 'atlas'." >&2; exit 2; }
compose() { docker compose -p "$projeto" -f compose.yml "$@"; }

(cd "$origem" && sha256sum --check --quiet SHA256SUMS) || { echo "Cópia corrompida ou incompleta." >&2; exit 1; }
mkdir -p "$documentos"
[[ -z "$(ls -A "$documentos")" ]] || { echo "A pasta de documentos precisa estar vazia." >&2; exit 2; }

compose exec -T db createdb -U atlas "$banco"   # falha se já existir
compose exec -T db pg_restore -U atlas -d "$banco" --no-owner --exit-on-error < "$origem/banco.dump"
tar -xf "$origem/documentos.tar" -C "$documentos"
echo "Banco '$banco' e documentos em '$documentos' restaurados. Confira antes de trocar a configuração."
