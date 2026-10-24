#!/usr/bin/env bash
# Remove de vez um documento (aviso e retirada): banco, revisões, arquivo no disco e um registro em remocao_documento.
#   ./deploy/remover-documento.sh <uuid-do-documento> "motivo (ex.: aviso de DD/MM/AAAA)"
# O arquivo só é apagado se nenhuma outra conta guarda o mesmo PDF (o nome é o hash). Faça uma cópia antes
# (deploy/backup.sh): a remoção não tem volta. Variável: ATLAS_PROJETO (padrão "atlas").
set -euo pipefail
cd "$(dirname "$0")"
id="${1:?Informe o UUID do documento}"
motivo="${2:?Informe o motivo}"
projeto="${ATLAS_PROJETO:-atlas}"
[[ "$id" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] || { echo "UUID inválido." >&2; exit 2; }
compose() { docker compose -p "$projeto" -f compose.yml "$@"; }
# -q: sem as etiquetas de comando (BEGIN, DELETE n); as variáveis só são interpoladas no SQL lido da entrada padrão.
psql_() { compose exec -T db psql -q -U atlas -d atlas -v ON_ERROR_STOP=1 -At "$@"; }

linha="$(psql_ -v id="$id" <<<"SELECT sha256 || ' ' || chave_armazenamento FROM versao_documento WHERE id = :'id';")"
[[ -n "$linha" ]] || { echo "Documento não encontrado." >&2; exit 1; }
read -r sha chave <<<"$linha"
[[ "$chave" =~ ^[0-9a-f]{64}\.pdf$ ]] || { echo "Chave de armazenamento inesperada: $chave" >&2; exit 1; }

# Uma transação só. As revisões são imutáveis por gatilho; o operador o desliga APENAS aqui, dentro da transação
# (DDL é transacional no PostgreSQL: se algo falhar, o gatilho continua ligado).
restantes="$(psql_ -v id="$id" -v sha="$sha" -v motivo="$motivo" <<'SQL'
BEGIN;
ALTER TABLE revisao_medida DISABLE TRIGGER revisao_imutavel;
DELETE FROM revisao_medida WHERE medida_id IN (
  SELECT m.id FROM medida m JOIN experimento x ON x.id = m.experimento_id JOIN estudo s ON s.id = x.estudo_id
  WHERE s.versao_documento_id = :'id');
ALTER TABLE revisao_medida ENABLE TRIGGER revisao_imutavel;
WITH apagado AS (DELETE FROM versao_documento WHERE id = :'id' RETURNING id, sha256, dono_id)
INSERT INTO remocao_documento(documento_id, sha256, dono_id, motivo, arquivo_removido)
SELECT id, sha256, dono_id, :'motivo', NOT EXISTS (SELECT 1 FROM versao_documento WHERE sha256 = :'sha' AND id <> :'id') FROM apagado;  -- o CTE não enxerga a própria exclusão
COMMIT;
SELECT count(*) FROM versao_documento WHERE sha256 = :'sha';
SQL
)"
restantes="$(tail -n1 <<<"$restantes")"
if [[ "$restantes" == "0" ]]; then
  docker run --rm -v "${projeto}_documentos:/dados" --entrypoint rm postgres:18 -f "/dados/$chave"
  echo "Documento $id removido do banco e o arquivo $chave apagado."
else
  echo "Documento $id removido do banco. O arquivo $chave foi MANTIDO: $restantes outra(s) conta(s) guardam o mesmo PDF."
fi
