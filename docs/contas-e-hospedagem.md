# Contas, armazenamento e hospedagem pública: plano para o Codex

Nota original do revisor (2026-10-24). Este plano define critérios para abrir o projeto a mais de uma pessoa. A **Fase A foi implementada na continuação**, com verificações locais por classe e CI ainda dependente da revisão/push do Claude. As fases B e C não foram implementadas nesta tarefa. Testes e limites estão no roteiro e no handoff.

## Ponto de partida (antes da Fase A)

- Uma chave compartilhada (`X-Atlas-Chave`) protege `/api/**`. Quem a tem lê e grava tudo; não há usuário, dono nem registro de quem fez o quê.
- `autor_declarado` nas revisões e `REPORTADO` nas medidas são texto livre: nada prova quem afirmou.
- Os PDFs ficam em um diretório local, com o hash como nome. O parser em subprocesso exige um arquivo local.
- Não há limite de taxa nem cota por usuário. O OpenAlex tem limite por instância, não por quem consulta.

## Fase A — contas e dono por recurso (antes de qualquer acesso público)

1. **Esquema (V16+).** `usuario(id, email único, senha_hash, criado_em)`; `dono_id` em `versao_documento` e `colecao`; `usuario_id` em `revisao_medida` e nas execuções de processamento. Obras e metadados vindos de fontes públicas continuam compartilhados; documentos, coleções, medidas e revisões passam a ter dono. Migração dos dados existentes: um usuário "operador legado" recebe tudo, sem apagar nada.
2. **Autenticação.** Hash de senha com Argon2id (`spring-security-crypto`, sem adotar o Spring Security inteiro, a menos que o autor queira). Sessão por token opaco guardado como hash no banco, em cookie `HttpOnly; Secure; SameSite=Strict`, com proteção contra CSRF nas rotas que alteram estado. Opções e riscos: JWT dispensa o banco mas não pode ser revogado antes de expirar; sessão opaca exige uma consulta por requisição.
3. **Autorização.** Todo acesso por `documento`, `execucao`, `medida`, `coleção` e `tarefa` verifica o dono. Recurso de outro usuário responde **404**, não 403, para não revelar que existe.
4. **Teste obrigatório de IDOR.** Para cada rota com id na URL: o usuário B tenta ler e gravar o recurso do usuário A e recebe 404; o do usuário A continua funcionando. A rota nova sem esse teste não passa na revisão.
5. **Procedência.** `autor_declarado` passa a ser preenchido pelo usuário autenticado; o campo livre deixa de ser aceito do cliente.
6. **Cadastro.** Configurável: `ABERTO`, `LISTA` ou `FECHADO`. O Vitor escolheu **aberto**, nesta tarefa, após a pergunta explícita. Limites por usuário/IP e cotas continuam na Fase B, não nesta implementação.

Critério de conclusão: suíte com os testes de IDOR verdes no CI, migração testada sobre dados antigos, e chave compartilhada reduzida a segredo de operação. O teste V16 sobre V15 populada passou localmente; o critério de CI continua pendente até o Claude revisar e publicar os commits.

### Contrato implementado na Fase A

- `GET /api/auth/sessao` cria pré-sessão anônima, se necessário, e retorna conta/CSRF; não expõe token de sessão nem senha/hash.
- `POST /api/auth/cadastro`, `/login` e `/logout` exigem cookie e `X-Atlas-CSRF`; login/cadastro recebem JSON. Login/cadastro trocam o token, logout apaga a sessão. Origem divergente e `Sec-Fetch-Site: cross-site` são recusados nas alterações.
- Cookie `__Host-atlas-sessao`, `HttpOnly; Secure; SameSite=Strict; Path=/`, sem Domain. Token aleatório de 256 bits, apenas SHA-256 no banco. Pré-sessão expira em 30 minutos; sessão autenticada em 8 horas, sem renovação deslizante. Linhas expiradas não são removidas automaticamente nesta versão.
- Argon2id: 19 MiB, 2 iterações, paralelismo 1, salt 16 bytes e saída 32 bytes; `spring-security-crypto` e Bouncy Castle 1.86. Custo não calibrado sob carga. Não foi instalado o framework Spring Security completo.
- Toda API exige sessão, inclusive catálogo de fontes compartilhadas. Recursos privados alheios retornam 404; comparação também valida IDs na query. Coleções, tarefas e resumo operacional filtram por dono. Métricas globais exigem **sessão mais** a chave de operação.
- Obras e citações da fonte continuam compartilhadas: seus três endpoints com ID têm teste de acesso por B, sem inventar dono de metadados públicos. Associar um documento a obra continua privado.
- Execuções e medidas registram `usuario_id`; revisão usa a conta da sessão, grava seu UUID também no campo histórico `autor_declarado` e recusa autoria do cliente. Texto de autor em revisões antigas permanece intacto, atribuído ao legado. Não há verificação externa de identidade ou posse do e-mail.

### Configuração e decisões do autor

| Variável | Comportamento |
|---|---|
| `ATLAS_CADASTRO` | `ABERTO` por escolha do autor; permite `LISTA` ou `FECHADO` |
| `ATLAS_EMAILS_PERMITIDOS` | E-mails separados por vírgula, usados em `LISTA`; normalizados sem espaços e sem distinção de caixa |
| `ATLAS_PDF_ORIGINAL` | `DESABILITADO` por padrão; `SOMENTE_DONO` reserva a política de eventual download. **Nenhum valor cria rota de PDF nesta fase** |
| `ATLAS_CHAVE_API` | Segredo adicional para `/api/operacao/metricas`; ausente mantém essa rota recusada |

O autor permitiu **compartilhamento posteriormente**. Não foi criada rota de original nem compartilhamento entre contas. A configuração não permite compartilhamento entre pessoas nesta fase.

`operador-legado@atlas.invalid` recebe os dados antigos e fica desativado, sem senha utilizável. Cadastro não pode reivindicá-lo. Não há transferência para a primeira pessoa que se cadastrar; vincular o legado a uma conta real exige operação administrativa futura expressamente autorizada. V16 não é retrocompatível com escritores V15 sem dono: não manter o binário antigo gravando após a migração.

Fontes: [senhas no Spring](https://docs.spring.io/spring-security/reference/7.0/features/authentication/password-storage.html), [CSRF com pré-sessão de login](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html) e [Bouncy Castle](https://www.bouncycastle.org/download/bouncy-castle-java/). Não substituem os testes nem revisão independente.

## Fase B — limites para quem é público

- Limite de taxa por usuário e por IP em upload, processamento e nas rotas que chamam o OpenAlex.
- Cota de armazenamento e de documentos por usuário (o autor quer o maior número possível de documentos; a cota é um valor ajustável, não uma proibição).
- TLS no proxy reverso; a porta do compose continua em 127.0.0.1.
- Backups do PostgreSQL e do armazenamento de documentos com restauração testada: hoje não há.

## Fase C — armazenamento de objetos (quando houver mais de uma instância)

- Interface `ArmazenamentoDocumentos` (`gravar`, `abrirLocal`, `existe`) com a implementação em disco atual e uma em S3/MinIO. A chave de objeto já é só o nome derivado do hash, o que torna a gravação idempotente.
- Ordem no upload: gravar o objeto, depois o registro. Objeto sem registro é lixo recuperável; registro sem objeto é erro.
- O parser precisa de arquivo local: o worker baixa para o `tmpfs` antes de iniciar o subprocesso e apaga depois.
- Teste com MinIO em Testcontainers no CI (não localmente, por memória).

## Risco de conteúdo (fato, sem juízo)

Guardar PDFs de terceiros é uma coisa; devolvê-los a outras pessoas é outra. O autor respondeu que permite compartilhamento posteriormente. A Fase A não devolve originais nem os compartilha. Permissão de armazenar/processar não deve ser tratada automaticamente como permissão de redistribuir; o contrato de compartilhamento ainda não foi implementado.

## O que continua sem medição

Memória dos contêineres sob carga, custo de hospedagem, desempenho das listagens com milhares de obras e o smoke Docker do healthcheck do worker.
