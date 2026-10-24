# Nota de passagem para o Codex

Escrita em 2026-10-24 por quem faz a revisão e os pushs deste repositório. Leia antes de continuar a implementação: várias regras mudaram desde os últimos commits da implementação, e os testes novos vão reprovar código que as ignore.

## O que aconteceu

O projeto foi revisado em duas etapas.

1. Uma revisão sequencial dos commits da implementação (`docs/revisao-cruzada.md`, seções de 2026-10-24).
2. Uma revisão em paralelo por quatro agentes independentes, cada um com uma lente e somente leitura: **segurança**, **escalabilidade**, **modelo de dados e arquitetura**, **testes e operação**. A orquestração foi: um agente coordenador distribuiu o escopo, recebeu os quatro relatórios, **conferiu cada achado no código antes de aceitá-lo** e só então corrigiu. Dois achados não se confirmaram e foram registrados como tal.

Cada correção é um commit pequeno cuja mensagem explica o motivo e o limite do que foi verificado. A tabela completa, com os hashes, está em `docs/revisao-cruzada.md`.

## Regras que agora valem no código

| Regra | Onde | Efeito se você a quebrar |
|---|---|---|
| Medida `REPORTADO` precisa ter o valor dentro do trecho (422) | `ProcessadorDocumentoService.registrarMedida` | O teste `medidaReportadaPrecisaTerOValorNoTrecho` reprova. |
| Trecho que aparece mais de uma vez na página é recusado (409) | idem | `rejeitaTrechoQueApareceMaisDeUmaVezNaPagina` reprova. |
| Campos e condições têm tamanho máximo (400) | `validarTamanhos` | `rejeitaCamposAcimaDoLimite` reprova. |
| PDF com mais de `atlas.documentos.paginas-maximas` páginas (padrão 500) é recusado antes de extrair | `ExtratorTextoPdf`, `UploadProperties` | `recusaPdfComMaisPaginasQueOLimite` reprova. |
| `chave_armazenamento` é só `<sha256>.pdf`, relativo ao diretório configurado | `UploadDocumentoService`, `caminhoSeguro` | `guardaApenasONomeDoArquivo...` reprova. |
| Apagar a única evidência de uma medida não `AUSENTE` falha no commit | migração V8 | `EsquemaTest` reprova. |
| Upload sem confirmação não entra, mesmo com obra | migração V8 | idem |
| Medida repetida (mesmo experimento, métrica, estado, valor, unidade) devolve a existente; outra citação vira outra evidência | `registrarMedida`, V9 | `reenviarAMesmaMedida...` reprova. |
| Trecho tolera só espaço em branco; o trecho guardado é o da página | `LocalizadorDeTrecho` | `LocalizadorDeTrechoTest` e `aceitaTrechoQueAtravessaQuebraDeLinha...` reprovam. |
| `/api/**` exige `X-Atlas-Chave` quando `ATLAS_CHAVE_API` está definida | `FiltroChaveApi` | `ChaveApiTest` reprova. |
| Erros saem como problem+json (`detail`) | `application.properties` | A tela deixa de mostrar o motivo. |
| Upload não abre transação em volta do arquivo | `UploadDocumentoService.receber` | Só aparece sob carga; não recoloque `@Transactional` ali. |

Migrações aplicadas **não se editam** (quebra o checksum do Flyway); crie a próxima (atualmente `V10`).

## Como trabalhar a partir daqui

- Antes de editar: `git fetch` e `git status`. O push é feito por quem revisa; commite localmente e avise, ou envie se for combinado.
- Um assunto por commit, com a mensagem dizendo **por que** e o que **não** foi verificado. Sem coautoria nos commits.
- Rode `./mvnw test` em `api/` (precisa de Docker). Para rodar a API: `docker compose up -d` na raiz e `./mvnw spring-boot:run` em `api/`.
- A suíte agora inclui `InterfaceNavegadorTest`, com Playwright 1.63.0 e Chromium. Instale o navegador como documentado no README antes do primeiro `./mvnw test`; defina `PLAYWRIGHT_SKIP_BROWSER_GC=1` para preservar outras versões do cache. O CI faz a instalação com dependências do sistema.
- Cuidados nos testes: o MockMvc não segue para `/error`, então leia a mensagem com `getResolvedException()` (há o helper `motivo` em `ProcessamentoDocumentoTest`). O banco do Testcontainers é compartilhado entre os testes da classe; conte linhas **por execução**, nunca a tabela inteira.
- O Dependabot abriu PRs para atualizar as actions fixadas por hash. São saltos de versão maior; só aceite depois de ver o CI passar.

## O que NÃO foi decidido (é do autor do projeto)

O autor delegou ao revisor as decisões de projeto; o que foi decidido e o que foi adiado, com o motivo, está em `docs/decisoes.md`. Não implemente os itens adiados (contas, fila assíncrona, S3, procedência) sem alinhar: mudam o contrato da API. A exclusão de dados e a LGPD são decisão do autor: não implementar por conformidade.

## Honestidade nos textos públicos

O README, o roteiro e qualquer post só podem afirmar o que o código e os testes sustentam. Estados do roteiro: planejado, implementado, testado, medido. Nada neste projeto foi **medido** (desempenho, precisão de extração); estimativas dos revisores estão marcadas como estimativas.

## Continuação em 2026-10-24

- `d96a061` adiciona um teste real de navegador para upload de PDF sintético, registro de medida e abertura/destaque da evidência. Playwright 1.63.0 e Chromium são dependências de teste; o CI os instala antes da suíte. `./mvnw test` passou localmente com 52 testes. A execução do CI GitHub após o push ainda precisa ser conferida pelo revisor.
- O teste cobre só Chromium e o fluxo principal com PDF pesquisável. Não afirma compatibilidade com outros navegadores, responsividade, nem validação OCR. A limitação de tempo da extração segue aberta.

## Continuação: recuperação da tela e início da entrega 2

O autor autorizou tomar decisões visando a qualidade do projeto e pediu para questionar essas decisões com evidência. Os commits abaixo estão locais, à espera da revisão/push:

- `5fe2575`: limpa documento, páginas, resultados e formulário ao começar/falhar novo upload; bloqueia troca durante gravação de medida. O teste de página vazia revelou que PDFBox pode retornar somente uma quebra de linha; agora a tela mostra o aviso. Chromium cobre fluxo principal, OCR, recuperação de chave e troca de documento.
- `5693848`: `GET /api/catalogo/obras/por-doi` e conector OpenAlex. Consulta somente metadados. DOI normalizado, origem fixa, chave opcional do provedor em Bearer, prazo HTTP completo, intervalo por instância e cooldown em 429. Testes reproduziram que `HttpRequest.timeout` sozinho não cobre corpo lento no Java 21; o prazo agora cancela a operação completa.
- `9ff978b`: busca por DOI na tela; fonte dos metadados visível, texto externo sem interpretação de HTML e recuperação em erro.
- `./mvnw test` passou com **86 testes**. Os testes da fonte usam servidor HTTP local e o navegador usa respostas controladas da busca. Verificação manual adicional pela API local contra o OpenAlex real: dois DOIs dos candidatos responderam 200, com IDs `W4220682882` e `W4220906497`. DOI inválido respondeu 400 com `detail`.

O contrato, a configuração, os erros e os limites estão em [openalex.md](openalex.md). Persistência/deduplicação do catálogo, busca por tema/autor e coleções ainda não foram implementadas. Os dois artigos de demonstração tiveram a licença do XML reconferida; não foi validado um conjunto de avaliação nem baixado PDF nesta rodada. A suíte passou localmente; o CI remoto após estes commits ainda precisa ser conferido. Sem post durante o trabalho: o fechamento do dia reúne os fatos para revisão do Vitor.

## Nova rodada: roteiro completo autorizado, orçamento zero

O usuário autorizou tomar decisões e avançar no roteiro inteiro. Isso supera a restrição histórica de não iniciar fila sem alinhamento: o alinhamento ocorreu. Depois esclareceu que não quer gastar nenhum real. Não criar AWS, serviços pagos ou chamadas pagas. Contas/propriedade, armazenamento S3 e exclusão seguem sem implementação. A próxima migração livre é **V15**; não alterar V1–V14.

Commits locais para revisão/push do Claude (não houve push do implementador):

- `f4d7a11`, `a4cd344`, `99c3818`: catálogo persistido, snapshots de fontes, conflitos explícitos, busca por tema/autor/ano, coleções do operador, associação explícita de PDF e interface.
- `82fd90d`: parser em JVM filha com prazo, heap, concorrência limitada e ambiente sem segredos. Não é sandbox de SO.
- `d562fde`, `2153db7`, `827c53a`: fila PostgreSQL, eventos transacionais, reservas/token, reentrega idempotente, retentativas/falhas e UI com retomada. Teste de interrupção entre persistir e confirmar não duplica execução. Interrupção do parser vira 503 recuperável, não 422 permanente.
- `4a6b2e1`, `546413a`: revisões append-only e controle otimista, conversões decimais conservadoras, comparação/abertura da evidência. Identidade do revisor é declarada, não autenticada.
- `bc4aa02`, `f58da62`: snapshots de citações, grafo limitado, navegação por obra e linha do tempo; não há relações inferidas.
- `7b255f5`: painel operacional e métricas por processo com cardinalidade limitada.
- `598277a`: contêineres privados API/worker, non-root/read-only, banco sem porta pública, smoke sintético que remove só o seu próprio projeto temporário. JAR empacotado/filho validado de verdade, não apenas classes de teste.
- `881211d`: avaliador offline por campo/evidência/experimento e fixture negativa; não representa precisão de IA.

A suíte completa passou com **112 testes** antes da adição do avaliador e OpenTelemetry. Quatro testes do avaliador passaram, CLI negativa confirmou 1/2 campos completos, e teste de envio OTLP local passou. O estado final da suíte e do coletor fica no fechamento abaixo. Não executar dois Maven compilando o mesmo `target/` simultaneamente: uma tentativa assim interferiu no parser de navegador; a repetição isolada passou.

Pendências que não podem ser apresentadas como concluídas: avaliação científica com referência independente; extração automática por IA; OCR; conectores reais Unpaywall/Europe PMC; React/TS; contas individuais; S3; AWS/CD (orçamento zero); carga representativa e revisão/CI remoto dos novos commits. Não publicar uma afirmação de conclusão integral do roteiro. LinkedIn continua em uma publicação consolidada no fim do dia, revisada pelo autor, sem post por commit.

### Fechamento verificado desta rodada

- `7c9be0a`: OpenTelemetry opt-in; exportações externas desligadas por padrão. Receptor HTTP local verificou OTLP; override Jaeger e smoke Docker verificaram span real `atlas.pdf.execucao` do worker. Não há propagação de trace pela fila nem agregação remota de métricas.
- Última suíte completa, 2026-10-24 às 14:18 BRT: **117 testes, 0 falhas, 0 erros, 0 ignorados**, BUILD SUCCESS. Inclui seis fluxos Chromium, PostgreSQL real, parser em processo e teste OTLP. O Maven ainda avisa sobre metadados de dois repositórios opcionais 401 e escrita nativa no canal Surefire; não impediram o build e não foram suprimidos.
- Dois smokes com JAR real passaram, um sem e outro com coletor local. Mesmos uploads concorrentes mantiveram um documento/uma tarefa, e retomada do worker concluiu. Apenas os projetos/volumes sintéticos criados pelos smokes foram removidos; instâncias preexistentes não foram alteradas. As imagens de teste ficam no cache Docker.
- `git fetch origin` não mostrou commits remotos novos a integrar; não houve push, criação de PR, uso AWS, chamada paga ou publicação LinkedIn. Apontamentos atualizados somente no `linkedin.md` ignorado pelo Git.

## Próximo passo sugerido pelo revisor

O que falta é a abertura a mais de uma pessoa. O plano com critérios verificáveis está em [contas-e-hospedagem.md](contas-e-hospedagem.md): contas e dono por recurso (com teste de IDOR em toda rota com id), limites para público, e S3 só quando houver mais de uma instância. Cada fase deve entrar em commits pequenos, com a migração testada sobre dados antigos. A decisão sobre devolver ou não o PDF original a terceiros é do autor.
