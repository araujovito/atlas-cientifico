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
| Upload não abre transação em volta do arquivo | `UploadDocumentoService.receber` | Só aparece sob carga; não recoloque `@Transactional` ali. |

Migrações aplicadas **não se editam** (quebra o checksum do Flyway); crie a próxima (`V9`...).

## Como trabalhar a partir daqui

- Antes de editar: `git fetch` e `git status`. O push é feito por quem revisa; commite localmente e avise, ou envie se for combinado.
- Um assunto por commit, com a mensagem dizendo **por que** e o que **não** foi verificado. Sem coautoria nos commits.
- Rode `./mvnw test` em `api/` (precisa de Docker). Para rodar a API: `docker compose up -d` na raiz e `./mvnw spring-boot:run` em `api/`.
- Cuidados nos testes: o MockMvc não segue para `/error`, então leia a mensagem com `getResolvedException()` (há o helper `motivo` em `ProcessamentoDocumentoTest`). O banco do Testcontainers é compartilhado entre os testes da classe; conte linhas **por execução**, nunca a tabela inteira.
- O Dependabot abriu PRs para atualizar as actions fixadas por hash. São saltos de versão maior; só aceite depois de ver o CI passar.

## O que NÃO foi decidido (é do autor do projeto)

Autenticação e dono por documento, exclusão de dados e retenção (LGPD), processamento assíncrono, armazenamento em S3, paginação, medida repetida, procedência de quem afirmou, e a licença. A lista e o motivo de cada uma estão em `docs/revisao-cruzada.md`. Não implemente nenhuma sem confirmar com o autor: todas mudam o contrato da API ou uma regra do produto.

## Honestidade nos textos públicos

O README, o roteiro e qualquer post só podem afirmar o que o código e os testes sustentam. Estados do roteiro: planejado, implementado, testado, medido. Nada neste projeto foi **medido** (desempenho, precisão de extração); estimativas dos revisores estão marcadas como estimativas.
