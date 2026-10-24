# Roteiro

Acompanha a [especificação](especificacao.md). Cada passo termina com algo executável e um critério de conclusão verificável. Estados: **planejado**, **implementado**, **testado**, **medido**. Um passo só sobe de estado com a evidência ao lado.

## Entrega 1 — importar, processar e mostrar evidência

Objetivo: importar um PDF que a pessoa pode processar, extrair texto por página, guardar medidas com o trecho de origem e exibir a evidência. Reimportar o mesmo arquivo não duplica nada.

| # | Passo | Critério de conclusão | Estado |
|---|---|---|---|
| 1 | Esqueleto Spring Boot 4.1.1 / Java 21 com Postgres em Testcontainers | `./mvnw test` passa com Postgres real | testado |
| 2 | Esquema inicial (migração Flyway): obra, versão do documento, execução, estudo, experimento, medida, evidência | A migração aplica num banco vazio; constraints impedem medida sem evidência e hash duplicado (6 testes; sem o gatilho de evidência o teste correspondente reprova) | testado |
| 3 | Upload de PDF com consentimento explícito: valida assinatura, tamanho e hash; guarda o original com nome derivado do hash; devolve o registro | Testes de integração: duplicata não cria registro, PDF inválido, excesso de tamanho e ausência de consentimento são recusados | testado |
| 4 | Extração síncrona de texto por página (Apache PDFBox) | PDF multipágina persiste texto separado por página; PDF sem texto fica em `PRECISA_OCR` | testado |
| 5 | Execução de processamento com chave de idempotência (hash + versão do processador) | Reprocessar devolve a execução existente e não duplica páginas; medidas serão cobertas quando a etapa 6 for implementada | testado |
| 6 | Registro de medidas candidatas sem chamada paga | Testes de integração confirmam página e trecho literal, preservação de valor/unidade/condições e rejeição de evidência inexistente | testado |
| 7 | Endpoint e tela mínima que mostram uma medida e o trecho de origem | API lista medida e evidência; página inicial permite registrar e consultar o trecho | implementado |
| 8 | CI no GitHub Actions rodando os testes | Workflow configurado para push e pull request em `main`; aguarda execução no GitHub | implementado |

Dependências: Docker (presente), JDK 21 (instalado em `~/.local/jdk`, ver `docs/ambiente.md`).

Limitações conhecidas desta entrega: um domínio, poucos campos, sem OCR, sem Kafka (o processamento ainda é síncrono; a decisão de fila entra na entrega 3). O registro de medida é manual; extração automática e revisão humana ainda não estão implementadas. A interface existente é um protótipo mínimo, não o frontend React/TS previsto na especificação.

## Entregas seguintes

Seguem a seção 9 da especificação: 2 descoberta e catálogo, 3 processamento assíncrono robusto, 4 comparação e revisão, 5 rede de citações, 6 operação e AWS, 7 avaliação e medição. Cada uma ganha o seu quadro como esta quando a anterior terminar.

## Pendências transversais

- Completar e revisar o conjunto de referência de artigos de baterias antes de avaliar extração automática no passo 6. O candidato inicial e suas limitações estão em `docs/conjunto-referencia.md`.
- Confirmar limites de requisição e termos do Unpaywall e do Europe PMC antes da entrega 2.
