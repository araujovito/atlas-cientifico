CREATE TABLE revisao_medida (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    medida_id uuid NOT NULL REFERENCES medida(id),
    versao integer NOT NULL CHECK(versao>0),
    autor_declarado text NOT NULL CHECK(length(btrim(autor_declarado)) BETWEEN 1 AND 100),
    motivo text NOT NULL CHECK(length(btrim(motivo)) BETWEEN 1 AND 1000),
    anterior jsonb NOT NULL CHECK(jsonb_typeof(anterior)='object'),
    correcao jsonb NOT NULL CHECK(jsonb_typeof(correcao)='object'),
    registrado_em timestamptz NOT NULL DEFAULT now(),
    UNIQUE(medida_id,versao)
);
CREATE FUNCTION impedir_mutacao_revisao() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'revisões são imutáveis; acrescente uma nova revisão' USING ERRCODE='check_violation';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER revisao_imutavel BEFORE UPDATE OR DELETE ON revisao_medida
    FOR EACH ROW EXECUTE FUNCTION impedir_mutacao_revisao();
