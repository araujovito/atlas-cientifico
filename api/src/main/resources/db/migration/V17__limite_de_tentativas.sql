-- Contador por janela fixa, no banco para valer com mais de uma instância. A chave não guarda e-mail em claro.
CREATE TABLE limite_tentativa (
    chave text PRIMARY KEY CHECK (length(chave) BETWEEN 1 AND 200),
    janela_inicio timestamptz NOT NULL DEFAULT now(),
    contagem integer NOT NULL CHECK (contagem > 0)
);
CREATE INDEX limite_tentativa_janela_idx ON limite_tentativa(janela_inicio);
