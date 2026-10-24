ALTER TABLE execucao_processamento ADD CONSTRAINT execucao_id_documento_unico UNIQUE(id,versao_documento_id);
ALTER TABLE tarefa_extracao DROP CONSTRAINT tarefa_extracao_execucao_id_fkey;
ALTER TABLE tarefa_extracao ADD CONSTRAINT tarefa_execucao_do_documento
    FOREIGN KEY(execucao_id,documento_id) REFERENCES execucao_processamento(id,versao_documento_id);
