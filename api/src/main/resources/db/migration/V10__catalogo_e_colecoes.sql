-- Metadados de fontes são snapshots: mudanças não apagam o registro recebido antes.
CREATE TABLE obra_metadados (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    obra_id uuid NOT NULL REFERENCES obra(id) ON DELETE CASCADE,
    fonte text NOT NULL CHECK (fonte IN ('OPENALEX', 'UNPAYWALL', 'EUROPEPMC')),
    registro jsonb NOT NULL CHECK (jsonb_typeof(registro) = 'object'),
    sha256 char(64) NOT NULL,
    coletado_em timestamptz NOT NULL DEFAULT now(),
    UNIQUE (obra_id, fonte, sha256)
);
CREATE INDEX obra_metadados_ordem_idx ON obra_metadados(obra_id, coletado_em, id);

-- Coleções do operador da instância. Contas individuais exigirão propriedade explícita.
CREATE TABLE colecao (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    nome text NOT NULL CHECK (length(btrim(nome)) BETWEEN 1 AND 200),
    criado_em timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE colecao_obra (
    colecao_id uuid NOT NULL REFERENCES colecao(id) ON DELETE CASCADE,
    obra_id uuid NOT NULL REFERENCES obra(id) ON DELETE CASCADE,
    adicionada_em timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (colecao_id, obra_id)
);
CREATE INDEX colecao_obra_obra_idx ON colecao_obra(obra_id);
