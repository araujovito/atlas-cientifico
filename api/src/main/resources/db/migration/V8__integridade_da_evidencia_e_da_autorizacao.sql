-- 1. O gatilho do V1 só disparava ao inserir ou mudar o estado da medida. Apagar a
--    evidência (ou movê-la para outra medida) deixava uma medida REPORTADO sem origem.
--    Este gatilho confere, no commit, que a medida da evidência removida ainda tem alguma.
--    Se a própria medida foi apagada (cascata), não há o que exigir.
CREATE FUNCTION exigir_evidencia_remanescente() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM medida m WHERE m.id = OLD.medida_id AND m.estado <> 'AUSENTE')
       AND NOT EXISTS (SELECT 1 FROM evidencia e WHERE e.medida_id = OLD.medida_id) THEN
        RAISE EXCEPTION 'medida % ficaria sem evidência', OLD.medida_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER evidencia_remanescente
    AFTER DELETE OR UPDATE OF medida_id ON evidencia
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION exigir_evidencia_remanescente();

-- 2. O terceiro termo (obra_id IS NOT NULL) deixava um UPLOAD sem confirmação passar só por
--    ter obra. O V2 já preencheu autorizacao_confirmada_em nas linhas antigas.
ALTER TABLE versao_documento DROP CONSTRAINT upload_com_autorizacao_confirmada;
ALTER TABLE versao_documento ADD CONSTRAINT upload_com_autorizacao_confirmada
    CHECK (origem <> 'UPLOAD' OR autorizacao_confirmada_em IS NOT NULL);
