-- O mesmo material pode ser testado em diferentes taxas, temperaturas,
-- eletrólitos e cargas. Não misture esses resultados no mesmo experimento.
ALTER TABLE experimento DROP CONSTRAINT experimento_unico_por_objeto;
ALTER TABLE experimento ADD CONSTRAINT experimento_unico_por_objeto_e_condicoes
    UNIQUE (estudo_id, objeto, condicoes);
