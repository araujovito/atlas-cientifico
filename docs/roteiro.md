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
| 6 | Registro de medidas candidatas sem chamada paga | Testes de integração confirmam página, tolerância apenas a espaços/quebras de linha, offsets Unicode de início inclusivo/fim exclusivo, preservação de valor/unidade/condições, rejeição de evidência ausente ou ambígua e bloqueio de registros sem texto pesquisável (`PRECISA_OCR`) | testado |
| 7 | Endpoint e tela mínima que mostram uma medida e o trecho de origem | API lista medida, página, trecho e offsets; teste em Chromium envia um PDF gerado, registra uma medida e confirma a abertura e o destaque da evidência. Evidências anteriores à V6 mantêm offsets nulos; em `PRECISA_OCR`, informa a limitação e oculta o formulário | testado |
| 8 | CI no GitHub Actions rodando os testes | Workflow configurado para push e pull request em `main`; execuções verdes no GitHub em `f897ff0`, `95eb6f5` e `d47df5d` | testado |

Dependências: Docker (presente), JDK 21 (instalado em `~/.local/jdk`, ver `docs/ambiente.md`).

Limitações: um domínio, poucos campos, sem OCR ou extração automática por IA. O registro é manual. Revisão com histórico foi acrescentada na entrega 4 e fila na entrega 3. A interface é um protótipo HTML/JS, não o frontend React/TS previsto. O conjunto científico ainda depende de revisão independente.

## Entrega 2 — descoberta e catálogo

Objetivo: localizar obras, preservar identificadores e evitar duplicação ao montar um catálogo.

| # | Passo | Critério de conclusão | Estado |
|---|---|---|---|
| 1 | Consulta OpenAlex por DOI normalizado | API retorna título, autores, ano e identificadores; testes HTTP locais cobrem erros, limite de chamadas e prazo do corpo completo. Dois DOIs dos candidatos consultados também pela API local contra a fonte real | testado |
| 2 | Busca por DOI na tela mínima | Chromium mostra metadados externos como texto e oculta o resultado anterior em caso de falha; fonte e links são visíveis | testado |
| 3 | Persistir obra e identificadores com deduplicação | DOI/OpenAlex equivalentes não duplicam; conflitos retornam 409; snapshots preservados; associação explícita de PDF | testado — `f4d7a11`, testes catálogo/DB |
| 4 | Busca por tema/autor/ano e paginação | Resultados limitados; autor selecionado por ID; conteúdo externo não vira HTML | testado — `a4cd344`, `99c3818`, HTTP local e API. A tela de busca por tema/autor/ano e a paginação não têm teste de navegador (verificado na revisão de 2026-10-24) |
| 5 | Coleções do operador | Associação idempotente e persistente; sem contas individuais | testado — `f4d7a11`, `99c3818` |

Evidência dos passos 1 e 2: `5693848`, `9ff978b` e [contrato/verificações do OpenAlex](openalex.md). Deduplicação entre fontes foi testada com fixtures controladas; somente OpenAlex tem conector real. Isso não demonstra uma segunda integração externa.

## Entrega 3 — processamento assíncrono robusto

| Passo | Estado e evidência |
|---|---|
| Isolar parser com prazo e heap | testado — `82fd90d`: JVM lenta encerrada de verdade; não é sandbox de SO |
| Fila transacional + eventos no PostgreSQL | testado — `d562fde`: SKIP LOCKED, reserva, token, backoff e falhas retidas; Kafka dispensado nesta fase |
| Reentrega idempotente | testado — recuperação após persistir e antes de confirmar, duas reservas concorrentes e rejeição de token antigo |
| API 202 e acompanhamento/retomada na tela | testado — `2153db7`, Chromium |
| Interrupção recuperável | testado — `827c53a`, 503 sem execução incompleta |
| API/worker em contêineres distintos | testado — `598277a`, smoke de execução do JAR e trabalhador parado/reiniciado |

## Entrega 4 — comparação e revisão

| Passo | Estado e evidência |
|---|---|
| Normalização decimal preservando originais | testado — `4a6b2e1`, conversões limitadas e explícitas, sem float no JSON |
| Regras conservadoras de comparabilidade | testado — mesmas condições completas e unidade canônica; sem veredito científico |
| Revisão append-only com versão otimista | testado — banco recusa UPDATE/DELETE; conflito 409; origem permanece intacta |
| Matriz e histórico navegáveis | testado — `546413a`, API/DB e atualização da comparação no Chromium; o histórico de revisões na tela não é verificado pelo navegador. Revisor é declarado, não autenticado |

## Entrega 5 — rede de citações

| Passo | Estado e evidência |
|---|---|
| Snapshots de referências da fonte e expansão limitada | testado — `bc4aa02`, preservação de snapshots e mudanças da fonte |
| Grafo, navegação e linha do tempo | testado — `f58da62`, Chromium; ano/título desconhecidos ficam ausentes |
| Relações inferidas | não implementadas; todas as ligações atuais são identificadas como recebidas do OpenAlex |

## Entrega 6 — operação e implantação

| Passo | Estado e evidência |
|---|---|
| Painel da fila, eventos e reativação | testado — `7b255f5`, API/DB e Chromium |
| Métricas de tentativas com rótulos limitados | testado — `7b255f5`; contadores do processo, não agregação distribuída |
| OpenTelemetry opt-in e coletor local | testado — receptor OTLP em teste e span real do trabalhador recebido pelo Jaeger no smoke Docker; `operacao.md` |
| Empacotamento e Docker Compose privado | testado — `598277a`, API/worker separados, sem root e raiz somente leitura |
| CI | testes existentes; workflow novo após push depende de revisão do Claude |
| AWS e CD remoto | adiados pelo autor: orçamento zero; nenhum recurso criado |

## Entrega 7 — avaliação e medição

| Passo | Estado e evidência |
|---|---|
| Avaliador offline de campos e evidência | testado — `881211d`, quatro testes unitários do avaliador (mais um da ausência, `505d037`). A CLI e a fixture sintética existem, mas nenhum teste as executa |
| Método para referência independente | documentado em `avaliacao.md`; conjunto real ainda pendente |
| Concorrência e tempos operacionais sintéticos | medido em uma execução de smoke — `operacao.md`, oito uploads, método/hardware/limites registrados |
| Carga representativa e precisão científica | pendentes; não extrapolar a amostra sintética, não publicar nota de IA como validação |

## Pendências transversais

Última verificação local: 117 testes passaram sem falhas/erros/ignorados em 2026-10-24; smoke Docker com Jaeger também passou. Isso não conclui as pendências científicas, de produto e de implantação abaixo.

- Ler [a nota de passagem](handoff-codex.md) antes de continuar: ela lista as regras novas do código e as decisões que ainda são do autor.
- Chromium cobre upload/evidência/retomada, página sem texto, erro de chave, troca de documento, catálogo/coleções/citações, revisão/comparação e painel. Outros navegadores e tamanhos de tela continuam sem validação.
- Completar e revisar o conjunto de referência de artigos de baterias antes de avaliar extração automática no passo 6. O candidato inicial e suas limitações estão em `docs/conjunto-referencia.md`.
- Unpaywall: contrato e teto diário lidos; Europe PMC: limite por IP esclarecido, teto numérico ainda não confirmado explicitamente. Ver fontes em `openalex.md` antes de implementar os conectores. Somente OpenAlex está integrado.
