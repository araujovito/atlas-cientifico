CREATE TABLE tarefa_extracao (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    documento_id uuid NOT NULL REFERENCES versao_documento(id) ON DELETE CASCADE,
    chave text NOT NULL UNIQUE,
    estado text NOT NULL DEFAULT 'PENDENTE' CHECK (estado IN ('PENDENTE','EM_ANDAMENTO','CONCLUIDO','PRECISA_OCR','FALHOU')),
    tentativas integer NOT NULL DEFAULT 0 CHECK (tentativas >= 0),
    token uuid,
    reservado_ate timestamptz,
    disponivel_em timestamptz NOT NULL DEFAULT now(),
    execucao_id uuid REFERENCES execucao_processamento(id),
    detalhe text,
    criado_em timestamptz NOT NULL DEFAULT now(),
    finalizado_em timestamptz,
    CHECK ((estado='EM_ANDAMENTO' AND token IS NOT NULL AND reservado_ate IS NOT NULL)
        OR (estado<>'EM_ANDAMENTO' AND token IS NULL AND reservado_ate IS NULL)),
    CHECK ((estado IN ('CONCLUIDO','PRECISA_OCR','FALHOU')) = (finalizado_em IS NOT NULL)),
    CHECK (estado NOT IN ('CONCLUIDO','PRECISA_OCR') OR execucao_id IS NOT NULL)
);
CREATE INDEX tarefa_pendente_idx ON tarefa_extracao(disponivel_em,criado_em,id) WHERE estado='PENDENTE';
CREATE INDEX tarefa_reserva_idx ON tarefa_extracao(reservado_ate) WHERE estado='EM_ANDAMENTO';
CREATE INDEX tarefa_documento_idx ON tarefa_extracao(documento_id);
CREATE INDEX tarefa_execucao_idx ON tarefa_extracao(execucao_id);
CREATE TABLE tarefa_evento (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tarefa_id uuid NOT NULL REFERENCES tarefa_extracao(id) ON DELETE CASCADE,
    estado text NOT NULL,
    detalhe text,
    registrado_em timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX tarefa_evento_ordem_idx ON tarefa_evento(tarefa_id,id);
