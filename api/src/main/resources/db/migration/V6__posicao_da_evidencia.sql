-- Posições em pontos de código Unicode, com início inclusivo e fim exclusivo.
-- Evidências antigas permanecem sem posição; não inferimos offsets históricos.
ALTER TABLE evidencia
    ADD COLUMN inicio_caractere integer,
    ADD COLUMN fim_caractere integer,
    ADD CONSTRAINT evidencia_posicao_completa CHECK (
        (inicio_caractere IS NULL AND fim_caractere IS NULL)
        OR (inicio_caractere IS NOT NULL AND fim_caractere IS NOT NULL
            AND inicio_caractere >= 0 AND fim_caractere > inicio_caractere)
    );
