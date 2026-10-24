-- Um upload ainda sem metadados bibliográficos não deve criar uma obra fictícia.
-- A origem e a autorização pertencem ao documento recebido.
ALTER TABLE versao_documento
    ADD COLUMN titulo_declarado text,
    ADD COLUMN autorizacao_confirmada_em timestamptz;

UPDATE versao_documento
SET autorizacao_confirmada_em = coletado_em
WHERE autorizacao_declarada;

ALTER TABLE versao_documento
    ALTER COLUMN obra_id DROP NOT NULL,
    ALTER COLUMN chave_armazenamento SET NOT NULL,
    ADD CONSTRAINT upload_com_autorizacao_confirmada
        CHECK (origem <> 'UPLOAD' OR autorizacao_confirmada_em IS NOT NULL OR obra_id IS NOT NULL);
