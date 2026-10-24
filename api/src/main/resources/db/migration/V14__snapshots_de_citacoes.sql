CREATE TABLE citacao_snapshot (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    obra_id uuid NOT NULL REFERENCES obra(id) ON DELETE CASCADE,
    fonte text NOT NULL CHECK(fonte='OPENALEX'),
    origem_id text NOT NULL,
    referencias jsonb NOT NULL CHECK(jsonb_typeof(referencias)='array'),
    sha256 char(64) NOT NULL,
    coletado_em timestamptz NOT NULL DEFAULT now(),
    conferido_em timestamptz NOT NULL DEFAULT now(),
    UNIQUE(obra_id,fonte,sha256)
);
CREATE INDEX citacao_snapshot_obra_idx ON citacao_snapshot(obra_id,conferido_em,id);
