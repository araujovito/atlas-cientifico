#!/usr/bin/env bash
# Prova de ida e volta: cria dados, faz a cópia, restaura em outro banco e compara. Só sobe o PostgreSQL do compose.
# Não toca em nenhum ambiente existente: usa um projeto compose descartável.
set -euo pipefail
cd "$(dirname "$0")"
projeto="atlas-bkp-$$"
trabalho="$(mktemp -d)"
export ATLAS_DB_PASSWORD="$(head -c 18 /dev/urandom | od -An -tx1 | tr -d ' \n')" ATLAS_CHAVE_API=teste ATLAS_PROJETO="$projeto"
compose() { docker compose -p "$projeto" -f compose.yml "$@"; }
sql() { compose exec -T db psql -U atlas -d "${2:-atlas}" -v ON_ERROR_STOP=1 -At -c "$1"; }
limpar() { compose down -v --remove-orphans >/dev/null 2>&1 || true; docker volume rm "${projeto}_documentos" >/dev/null 2>&1 || true; rm -rf "$trabalho"; }
trap limpar EXIT
falhar() { echo "FALHOU: $*" >&2; exit 1; }
# Uma consulta que falha tem de derrubar o teste; dentro de [[ ]] o set -e não pegaria.
val() { local saida; saida="$(sql "$@" 2>&1)" || falhar "consulta falhou: $1 -> $saida"; printf '%s' "$saida"; }

compose up -d --wait db >/dev/null
for migracao in $(ls ../api/src/main/resources/db/migration/V*.sql | sort -V); do
  compose exec -T db psql -U atlas -d atlas -v ON_ERROR_STOP=1 -q < "$migracao" >/dev/null
done

# Dados: uma conta, um documento com o arquivo no volume, e linhas que o backup NÃO deve levar.
hash="$(printf 'conteudo-do-pdf' | sha256sum | cut -d' ' -f1)"
docker run --rm -v "${projeto}_documentos:/dados" --entrypoint sh postgres:18 -c "printf 'conteudo-do-pdf' > /dados/$hash.pdf"
sql "WITH u AS (INSERT INTO usuario(email,senha_hash) VALUES ('bkp@example.com','x') RETURNING id)
     INSERT INTO versao_documento(origem,sha256,tamanho_bytes,chave_armazenamento,autorizacao_declarada,autorizacao_confirmada_em,dono_id)
     SELECT 'UPLOAD','$hash',15,'$hash.pdf',true,now(),id FROM u" >/dev/null
sql "INSERT INTO sessao(token_hash,usuario_id,csrf_token,criado_em,expira_em) SELECT '$hash',id,'c',now(),now()+interval '1 hour' FROM usuario WHERE email='bkp@example.com'" >/dev/null
sql "INSERT INTO limite_tentativa(chave,contagem) VALUES ('teste',1)" >/dev/null

# Três cópias com retenção 2: sobram as duas mais recentes.
for i in 1 2 3; do ATLAS_BACKUP_DIR="$trabalho/copias" ATLAS_BACKUP_MANTER=2 ./backup.sh >/dev/null; sleep 1.2; done
[[ "$(find "$trabalho/copias" -mindepth 1 -maxdepth 1 -type d | wc -l)" -eq 2 ]] || falhar "retenção não manteve 2 cópias"
copia="$(find "$trabalho/copias" -mindepth 1 -maxdepth 1 -type d | sort | tail -1)"

# Restauração em banco novo e pasta vazia.
ATLAS_RESTAURAR_DOCUMENTOS="$trabalho/documentos" ./restaurar.sh "$copia" >/dev/null
[[ "$(val 'SELECT count(*) FROM usuario' atlas_restaurado)" == "$(val 'SELECT count(*) FROM usuario')" ]] || falhar "usuários diferentes"
[[ "$(val 'SELECT count(*) FROM versao_documento' atlas_restaurado)" == "1" ]] || falhar "documento não restaurado"
# O esquema inteiro volta: mesmas tabelas, mesmos índices, mesmas restrições.
for consulta in "SELECT count(*) FROM pg_tables WHERE schemaname='public'" "SELECT count(*) FROM pg_indexes WHERE schemaname='public'" \
                "SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace WHERE n.nspname='public'" \
                "SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal"; do
  original="$(val "$consulta")"; restaurado="$(val "$consulta" atlas_restaurado)"
  [[ "$original" -gt 0 && "$original" == "$restaurado" ]] || falhar "esquema difere em: $consulta ($original x $restaurado)"
done
[[ "$(val 'SELECT count(*) FROM sessao' atlas_restaurado)" == "0" ]] || falhar "sessões voltaram no backup"
[[ "$(val 'SELECT count(*) FROM sessao')" == "1" ]] || falhar "a sessão de teste sumiu do banco original"
[[ "$(val 'SELECT count(*) FROM limite_tentativa' atlas_restaurado)" == "0" ]] || falhar "limites voltaram no backup"
[[ "$(sha256sum "$trabalho/documentos/$hash.pdf" | cut -d' ' -f1)" == "$hash" ]] || falhar "arquivo restaurado difere"

# Cópia adulterada: a restauração recusa e não cria banco.
cp -r "$copia" "$trabalho/adulterada"
printf 'x' >> "$trabalho/adulterada/banco.dump"
if ATLAS_RESTAURAR_BANCO=atlas_adulterado ATLAS_RESTAURAR_DOCUMENTOS="$trabalho/vazia" ./restaurar.sh "$trabalho/adulterada" >/dev/null 2>&1; then falhar "restaurou cópia adulterada"; fi
[[ "$(val "SELECT count(*) FROM pg_database WHERE datname='atlas_adulterado'")" == "0" ]] || falhar "criou banco para cópia adulterada"
# Recusa sobrescrever o banco de produção e pasta com conteúdo.
if ATLAS_RESTAURAR_BANCO=atlas ATLAS_RESTAURAR_DOCUMENTOS="$trabalho/vazia2" ./restaurar.sh "$copia" >/dev/null 2>&1; then falhar "aceitou restaurar sobre 'atlas'"; fi
if ATLAS_RESTAURAR_BANCO=atlas_outro ATLAS_RESTAURAR_DOCUMENTOS="$trabalho/documentos" ./restaurar.sh "$copia" >/dev/null 2>&1; then falhar "aceitou pasta de documentos com conteúdo"; fi
echo "OK: cópia, retenção, restauração e recusas verificadas."
