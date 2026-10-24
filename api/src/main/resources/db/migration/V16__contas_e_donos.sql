CREATE TABLE usuario (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email text NOT NULL UNIQUE CHECK (email=lower(btrim(email)) AND length(email) BETWEEN 3 AND 254),
    senha_hash text NOT NULL,
    ativo boolean NOT NULL DEFAULT true,
    criado_em timestamptz NOT NULL DEFAULT now()
);
-- Sem senha utilizável: o legado não pode ser reclamado através do cadastro/login.
INSERT INTO usuario(id,email,senha_hash,ativo)
VALUES ('00000000-0000-0000-0000-000000000001','operador-legado@atlas.invalid','!CONTA_LEGADA_DESABILITADA',false);

ALTER TABLE versao_documento ADD COLUMN dono_id uuid REFERENCES usuario(id);
UPDATE versao_documento SET dono_id='00000000-0000-0000-0000-000000000001';
ALTER TABLE versao_documento ALTER COLUMN dono_id SET NOT NULL;
ALTER TABLE versao_documento DROP CONSTRAINT versao_documento_sha256_key;
ALTER TABLE versao_documento ADD CONSTRAINT documento_hash_por_dono UNIQUE(dono_id,sha256);
ALTER TABLE versao_documento ADD CONSTRAINT documento_id_dono UNIQUE(id,dono_id);

ALTER TABLE colecao ADD COLUMN dono_id uuid REFERENCES usuario(id);
UPDATE colecao SET dono_id='00000000-0000-0000-0000-000000000001';
ALTER TABLE colecao ALTER COLUMN dono_id SET NOT NULL;
CREATE INDEX colecao_dono_ordem_idx ON colecao(dono_id,criado_em,id);

ALTER TABLE execucao_processamento ADD COLUMN usuario_id uuid REFERENCES usuario(id);
UPDATE execucao_processamento SET usuario_id='00000000-0000-0000-0000-000000000001',
    chave_idempotencia=versao_documento_id::text||':'||chave_idempotencia;
ALTER TABLE execucao_processamento ALTER COLUMN usuario_id SET NOT NULL;
ALTER TABLE execucao_processamento ADD CONSTRAINT execucao_do_dono
    FOREIGN KEY(versao_documento_id,usuario_id) REFERENCES versao_documento(id,dono_id);
UPDATE tarefa_extracao SET chave=documento_id::text||':'||chave;

ALTER TABLE medida ADD COLUMN usuario_id uuid REFERENCES usuario(id);
UPDATE medida SET usuario_id='00000000-0000-0000-0000-000000000001';
ALTER TABLE medida ALTER COLUMN usuario_id SET NOT NULL;
CREATE INDEX medida_usuario_idx ON medida(usuario_id);

ALTER TABLE revisao_medida ADD COLUMN usuario_id uuid REFERENCES usuario(id);
-- Apenas a migração acrescenta procedência histórica, sem alterar snapshots/textos.
ALTER TABLE revisao_medida DISABLE TRIGGER revisao_imutavel;
UPDATE revisao_medida SET usuario_id='00000000-0000-0000-0000-000000000001';
ALTER TABLE revisao_medida ENABLE TRIGGER revisao_imutavel;
ALTER TABLE revisao_medida ALTER COLUMN usuario_id SET NOT NULL;
CREATE INDEX revisao_usuario_idx ON revisao_medida(usuario_id);

CREATE TABLE sessao (
    token_hash char(64) PRIMARY KEY,
    usuario_id uuid REFERENCES usuario(id),
    csrf_token char(64) NOT NULL,
    criado_em timestamptz NOT NULL DEFAULT now(),
    expira_em timestamptz NOT NULL,
    CHECK(expira_em>criado_em)
);
CREATE INDEX sessao_usuario_idx ON sessao(usuario_id);
CREATE INDEX sessao_expiracao_idx ON sessao(expira_em);
