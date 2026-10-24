-- Listagens paginadas ordenam por (criado_em, id) ou (adicionada_em, obra_id); sem estes índices
-- cada página faz ordenação completa e descarta OFFSET linhas.
CREATE INDEX obra_ordem_idx ON obra(criado_em, id);
CREATE INDEX colecao_ordem_idx ON colecao(criado_em, id);
CREATE INDEX colecao_obra_ordem_idx ON colecao_obra(colecao_id, adicionada_em, obra_id);
