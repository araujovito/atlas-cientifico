-- Compartilhamento do PDF original, escolhido por documento pelo dono. Todo documento começa PRIVADO.
-- BLOQUEADO é decisão do operador (ex.: pedido de remoção) e o dono não consegue desfazer pela API.
ALTER TABLE versao_documento
    ADD COLUMN compartilhamento text NOT NULL DEFAULT 'PRIVADO' CHECK (compartilhamento IN ('PRIVADO', 'PUBLICO', 'BLOQUEADO'));
CREATE INDEX versao_documento_publico_idx ON versao_documento (coletado_em DESC, id) WHERE compartilhamento = 'PUBLICO';
