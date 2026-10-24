-- Cada medida registrada criava o seu próprio estudo e experimento, então cinco
-- medidas do mesmo artigo viravam cinco estudos. Um estudo passa a ser o
-- resultado de uma execução, e um experimento é identificado pelo objeto
-- estudado dentro do estudo.

-- Funde experimentos do mesmo objeto, deixando o de menor id como referência.
UPDATE medida m SET experimento_id = k.mantido
FROM (SELECT id, first_value(id) OVER (PARTITION BY estudo_id, objeto ORDER BY id) AS mantido
      FROM experimento) k
WHERE m.experimento_id = k.id AND k.id <> k.mantido;

-- Funde estudos da mesma execução, movendo seus experimentos para o de menor id.
UPDATE experimento x SET estudo_id = k.mantido
FROM (SELECT id, first_value(id) OVER (PARTITION BY execucao_id ORDER BY id) AS mantido
      FROM estudo) k
WHERE x.estudo_id = k.id AND k.id <> k.mantido;

-- Agora que os estudos foram fundidos, pode haver objetos repetidos no mesmo estudo.
UPDATE medida m SET experimento_id = k.mantido
FROM (SELECT id, first_value(id) OVER (PARTITION BY estudo_id, objeto ORDER BY id) AS mantido
      FROM experimento) k
WHERE m.experimento_id = k.id AND k.id <> k.mantido;

DELETE FROM experimento x
WHERE NOT EXISTS (SELECT 1 FROM medida m WHERE m.experimento_id = x.id)
  AND EXISTS (SELECT 1 FROM experimento o
              WHERE o.estudo_id = x.estudo_id AND o.objeto = x.objeto AND o.id < x.id);

DELETE FROM estudo s
WHERE NOT EXISTS (SELECT 1 FROM experimento x WHERE x.estudo_id = s.id)
  AND EXISTS (SELECT 1 FROM estudo o WHERE o.execucao_id = s.execucao_id AND o.id < s.id);

ALTER TABLE estudo ADD CONSTRAINT estudo_unico_por_execucao UNIQUE (execucao_id);
ALTER TABLE experimento ADD CONSTRAINT experimento_unico_por_objeto UNIQUE (estudo_id, objeto);
