-- Os ids são UUIDs aleatórios, então ordenar por id embaralhava as medidas a cada
-- consulta. clock_timestamp() (e não now()) distingue medidas de uma mesma transação.
ALTER TABLE medida ADD COLUMN registrada_em timestamptz NOT NULL DEFAULT clock_timestamp();
