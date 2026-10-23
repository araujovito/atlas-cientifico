-- Esquema inicial do Atlas Científico (entrega 1).
-- Regras de integridade ficam no banco para que nenhum caminho de código
-- consiga gravar uma medida sem evidência ou um documento duplicado.

CREATE TABLE obra (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    titulo     text NOT NULL CHECK (length(btrim(titulo)) > 0),
    ano        integer CHECK (ano BETWEEN 1500 AND 2200),
    criado_em  timestamptz NOT NULL DEFAULT now()
);

-- DOI, OpenAlex ID, PMID etc. O mesmo identificador não pode apontar para duas obras.
CREATE TABLE identificador (
    obra_id uuid NOT NULL REFERENCES obra (id) ON DELETE CASCADE,
    tipo    text NOT NULL CHECK (tipo IN ('DOI', 'OPENALEX', 'PMID', 'PMCID', 'ARXIV')),
    valor   text NOT NULL CHECK (length(btrim(valor)) > 0),
    PRIMARY KEY (obra_id, tipo, valor),
    UNIQUE (tipo, valor)
);

-- Um arquivo concreto de uma obra. O hash identifica o conteúdo: o mesmo
-- arquivo enviado duas vezes é o mesmo registro.
CREATE TABLE versao_documento (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    obra_id               uuid NOT NULL REFERENCES obra (id) ON DELETE CASCADE,
    origem                text NOT NULL CHECK (origem IN ('UPLOAD', 'OPENALEX', 'UNPAYWALL', 'EUROPEPMC')),
    versao                text NOT NULL DEFAULT 'DESCONHECIDA'
                          CHECK (versao IN ('PREPRINT', 'ACEITO', 'PUBLICADO', 'DESCONHECIDA')),
    sha256                char(64) NOT NULL UNIQUE CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    tamanho_bytes         bigint NOT NULL CHECK (tamanho_bytes > 0),
    chave_armazenamento   text NOT NULL,
    licenca               text,               -- nula quando desconhecida; nunca presumida
    autorizacao_declarada boolean NOT NULL CHECK (autorizacao_declarada),
    coletado_em           timestamptz NOT NULL DEFAULT now()
);

-- Uma tentativa de processar um documento com uma versão do processador.
-- A chave de idempotência impede duas execuções equivalentes.
CREATE TABLE execucao_processamento (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    versao_documento_id  uuid NOT NULL REFERENCES versao_documento (id) ON DELETE CASCADE,
    versao_processador   text NOT NULL,
    estado               text NOT NULL CHECK (estado IN
                         ('PENDENTE', 'EM_ANDAMENTO', 'PARCIAL', 'FALHOU',
                          'PRECISA_OCR', 'PRECISA_REVISAO', 'CONCLUIDO')),
    chave_idempotencia   text NOT NULL UNIQUE,
    detalhe              text,
    iniciado_em          timestamptz NOT NULL DEFAULT now(),
    finalizado_em        timestamptz,
    CHECK ((estado IN ('PENDENTE', 'EM_ANDAMENTO')) = (finalizado_em IS NULL))
);

CREATE TABLE pagina_texto (
    execucao_id uuid NOT NULL REFERENCES execucao_processamento (id) ON DELETE CASCADE,
    numero      integer NOT NULL CHECK (numero >= 1),
    texto       text NOT NULL,
    PRIMARY KEY (execucao_id, numero)
);

CREATE TABLE estudo (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    versao_documento_id  uuid NOT NULL REFERENCES versao_documento (id) ON DELETE CASCADE,
    execucao_id          uuid NOT NULL REFERENCES execucao_processamento (id) ON DELETE CASCADE
);

CREATE TABLE experimento (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    estudo_id   uuid NOT NULL REFERENCES estudo (id) ON DELETE CASCADE,
    objeto      text NOT NULL,                       -- material ou objeto estudado
    metodo      text,
    condicoes   jsonb NOT NULL DEFAULT '{}'::jsonb   -- ex.: taxa C, janela de tensão
);

-- O valor original é guardado como veio no texto e nunca é sobrescrito;
-- o normalizado é opcional e só existe com unidade.
CREATE TABLE medida (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    experimento_id       uuid NOT NULL REFERENCES experimento (id) ON DELETE CASCADE,
    metrica              text NOT NULL,
    valor_original       text,
    unidade_original     text,
    valor_normalizado    numeric,
    unidade_normalizada  text,
    incerteza            text,
    estado               text NOT NULL CHECK (estado IN
                         ('REPORTADO', 'INFERIDO', 'AUSENTE', 'PENDENTE_REVISAO')),
    -- Ausente significa sem valor; qualquer outro estado exige valor.
    CHECK ((estado = 'AUSENTE') = (valor_original IS NULL)),
    CHECK (valor_normalizado IS NULL OR unidade_normalizada IS NOT NULL)
);

CREATE TABLE evidencia (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    medida_id  uuid NOT NULL REFERENCES medida (id) ON DELETE CASCADE,
    pagina     integer NOT NULL CHECK (pagina >= 1),
    trecho     text NOT NULL CHECK (length(btrim(trecho)) > 0)
);

CREATE INDEX evidencia_medida_idx ON evidencia (medida_id);
CREATE INDEX experimento_estudo_idx ON experimento (estudo_id);
CREATE INDEX medida_experimento_idx ON medida (experimento_id);

-- Toda medida que não é AUSENTE precisa de pelo menos uma evidência no commit.
-- A verificação é adiada para o fim da transação para permitir inserir a
-- medida e a evidência na ordem que o código preferir.
CREATE FUNCTION exigir_evidencia() RETURNS trigger AS $$
BEGIN
    IF NEW.estado <> 'AUSENTE'
       AND NOT EXISTS (SELECT 1 FROM evidencia WHERE medida_id = NEW.id) THEN
        RAISE EXCEPTION 'medida % (%) sem evidência', NEW.id, NEW.estado
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER medida_exige_evidencia
    AFTER INSERT OR UPDATE OF estado ON medida
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION exigir_evidencia();
