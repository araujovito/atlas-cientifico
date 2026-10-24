# Operação local e orçamento

O autor definiu **orçamento de R$ 0**, em 2026-10-24. Nenhum recurso AWS será criado. Não se depende de oferta gratuita, cartão, assinatura de API ou crédito promocional. Implantação AWS e CD remoto continuam pendentes; não são declarados concluídos.

## Empacotar e executar

Requer Docker Compose, JDK 21 e Node 20 ou superior para o smoke. Primeiro rode os testes; não compile o mesmo `target/` em dois processos Maven ao mesmo tempo.

```bash
cd api
PLAYWRIGHT_SKIP_BROWSER_GC=1 ./mvnw test
./mvnw -DskipTests package
cd ..
docker build -f deploy/Dockerfile -t atlas-local:dev .
```

Para uma instância persistente, defina `ATLAS_DB_PASSWORD` e `ATLAS_CHAVE_API` com valores próprios no ambiente (não commite segredos), então:

```bash
docker compose -p atlas-local -f deploy/compose.yml up -d --wait
```

A interface fica em `http://127.0.0.1:8081/`. O banco não tem porta publicada. API e trabalhador são processos distintos com a mesma imagem; o trabalhador não abre servidor web e lê o volume de PDFs sem permissão de escrita. Aplicação não root, raiz somente leitura, `/tmp` limitado, capabilities removidas e limites de memória/CPU/processos. Isso não equivale a sandbox de segurança para código hostil nem a hospedagem pública pronta. O volume local e o banco não têm criptografia própria nem backup automatizado.

O parser é uma terceira JVM, sem variáveis de segredos herdadas, com heap limitado e prazo de parede. No empacotamento, a JVM filha usa o mesmo Boot JAR; o smoke verifica essa execução de verdade.

## Inspeção

- `GET /api/operacao`: contagem durável por estado e reservas expiradas.
- `GET /api/processamentos?estado=FALHOU&limite=50`: fila de falhas limitada.
- `GET /api/processamentos/{id}/eventos`: histórico.
- `POST /api/processamentos/{id}/retentar`: ação explícita que preserva eventos anteriores.
- `GET /api/operacao/metricas`: tempos/contagens Micrometer do processo atual, não totais históricos do banco. Em implantação separada, métricas do trabalhador precisam de coletor próprio; consultar a API não agrega processos.

Todos os endpoints `/api/` herdam a chave compartilhada. Apenas health é Actuator público. Não há contas ou permissão individual de administrador.

## Smoke reproduzível

```bash
node deploy/smoke.mjs
```

Cria projeto Compose temporário, senhas aleatórias e PDF inteiramente sintético. Faz oito uploads concorrentes e oito comandos repetidos, agenda com trabalhador parado, reinicia, confere texto e reutilização da execução. Ao terminar, **remove somente os contêineres e volumes desse projeto temporário**, contendo apenas a fixture sintética. Não toca no compose de desenvolvimento ou na instância persistente.

Uma execução em 2026-10-24 passou: PDF 846 bytes; oito uploads do mesmo conteúdo resultaram em um documento, uma tarefa e uma execução reutilizada. AMD Ryzen 7 7735HS, 16 CPUs lógicas, memória reportada 14.320.287.744 bytes, Node v20.19.2. API e worker limitados a 1 CPU/1 GiB cada, banco 512 MiB. Imagem da aplicação `sha256:237d81aaaa2f628d63bcdca46a488cec1b10b03d159f5419f3b364f1028477f2` (antes da adição de OpenTelemetry).

Tempos observados de upload: mínimo 307,08 ms, mediana 317,88 ms, máximo 343,77 ms. Retomada até conclusão: 16.494,81 ms, **incluindo partida da JVM e polling**, não somente extração. É uma amostra fria de um PDF mínimo repetido, não p95, capacidade, teste de carga representativo ou avaliação científica. Não extrapolar para artigos reais. Interrupção após persistir e antes de confirmar é coberta separadamente em `FilaRecuperacaoRealTest`; o smoke não mata o parser durante a leitura.
