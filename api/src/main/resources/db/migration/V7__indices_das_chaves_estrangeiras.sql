-- O PostgreSQL não cria índice para chave estrangeira. Estas três colunas são usadas em
-- filtros e em joins (buscar execução do documento, listar medidas por documento) e nos
-- ON DELETE CASCADE a partir de versao_documento, que sem índice varrem a tabela filha inteira.
CREATE INDEX execucao_processamento_documento_idx ON execucao_processamento (versao_documento_id);
CREATE INDEX estudo_documento_idx ON estudo (versao_documento_id);
CREATE INDEX versao_documento_obra_idx ON versao_documento (obra_id);
