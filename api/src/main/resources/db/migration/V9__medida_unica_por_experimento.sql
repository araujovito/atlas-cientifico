-- Registrar a mesma medida duas vezes (um reenvio do cliente, por exemplo) criava duas linhas.
-- Uma medida passa a ser identificada por experimento, métrica, estado, valor e unidade originais.
-- A mesma medida citada em dois lugares do artigo vira uma medida com duas evidências.

-- 1. Funde duplicatas já existentes, movendo as evidências para a medida mais antiga.
--    O gatilho de evidência remanescente (V8) é adiado até o commit, quando as sobras já foram apagadas.
UPDATE evidencia e SET medida_id = k.mantida
FROM (SELECT id, first_value(id) OVER (
          PARTITION BY experimento_id, metrica, estado, COALESCE(valor_original, ''), COALESCE(unidade_original, '')
          ORDER BY registrada_em, id) AS mantida
      FROM medida) k
WHERE e.medida_id = k.id AND k.id <> k.mantida;

DELETE FROM medida m
USING (SELECT id, first_value(id) OVER (
          PARTITION BY experimento_id, metrica, estado, COALESCE(valor_original, ''), COALESCE(unidade_original, '')
          ORDER BY registrada_em, id) AS mantida
       FROM medida) k
WHERE m.id = k.id AND k.id <> k.mantida;

-- 2. Evidências repetidas (mesma medida, página e posição) após a fusão.
DELETE FROM evidencia a USING evidencia b
WHERE a.medida_id = b.medida_id AND a.pagina = b.pagina
  AND a.inicio_caractere = b.inicio_caractere AND a.fim_caractere = b.fim_caractere
  AND a.id > b.id;

CREATE UNIQUE INDEX medida_unica_idx ON medida
    (experimento_id, metrica, estado, (COALESCE(valor_original, '')), (COALESCE(unidade_original, '')));

-- Evidências antigas sem posição não entram: sem offsets não há como dizer que são a mesma.
CREATE UNIQUE INDEX evidencia_unica_idx ON evidencia (medida_id, pagina, inicio_caractere, fim_caractere)
    WHERE inicio_caractere IS NOT NULL;
