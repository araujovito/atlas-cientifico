-- Registro das remoções feitas pelo operador (aviso e retirada). Guarda o suficiente para provar o que foi
-- removido e quando, sem o conteúdo do documento nem o título. Sem chave estrangeira: o documento deixa de existir.
CREATE TABLE remocao_documento (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    documento_id uuid NOT NULL,
    sha256 char(64) NOT NULL,
    dono_id uuid,
    motivo text NOT NULL CHECK (length(btrim(motivo)) BETWEEN 1 AND 1000),
    arquivo_removido boolean NOT NULL,
    removido_em timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX remocao_documento_sha_idx ON remocao_documento(sha256);
