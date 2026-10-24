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

### Revisão independente da Fase A (2026-10-24, segundo agente)

Dois agentes leram o código sem executá-lo (autenticação e sessão; autorização por rota); conferi os achados antes de agir. Não encontraram IDOR explorável: o interceptor `AutorizacaoRecursos` nega por padrão, as 15 rotas privadas têm teste de B=404, e o dedupe por `(dono, sha256)` não revela documentos alheios.

Corrigido nesta revisão: caminho com `..` agora é normalizado antes do filtro; `Origin` do domínio público atrás de proxy TLS é aceito por configuração (`ATLAS_ORIGENS_PERMITIDAS`), sem confiar em `X-Forwarded-*`; sessões vencidas são apagadas e há teto de 5000 pré-sessões anônimas; o login renova a pré-sessão vencida.

**Limites conhecidos que continuam (Fase B ou decisão):**

- **Limite de tentativas: implementado depois da revisão** (V17, `LimiteTentativas`; ver a seção abaixo). Os valores são escolhas do revisor, sem medição de tráfego.
- **Enumeração de e-mails.** O cadastro devolve 409 para e-mail existente e 201 para novo; com `LISTA`, o 403 revela quem está na lista. É inerente ao cadastro aberto sem confirmação por e-mail.
- **Cookie `__Host-`.** Exige `Secure`: funciona em `http://localhost` e `127.0.0.1` nos navegadores atuais, mas é descartado em `http://` com IP de rede local e no Safari. Para qualquer uso fora da máquina, TLS é obrigatório.
- **Autorização só no interceptor.** Os serviços (`FilaExtracaoService`, `ProcessadorDocumentoService`, `RevisaoService`, `associarDocumento`) não verificam o dono. Um chamador novo fora do interceptor (job, rota com variável de outro nome) vazaria. Não há esse caminho hoje; falta teste direto de serviço com dono errado.
- **Chave de objeto compartilhada.** Dois donos com o mesmo PDF apontam para o mesmo `hash.pdf`. Exclusão por documento exigirá contar referências.
- **Citações públicas.** `POST /api/catalogo/obras/{obra}/citacoes` aciona consulta externa para qualquer conta: abuso de cota do OpenAlex, não vazamento.
- **Sessão de 8 h absoluta**, sem renovação nem revogação em massa.
- Não testado: atrás de proxy real, em Safari/Firefox, nem o smoke Docker com contas.

### Limite de tentativas (implementado em 2026-10-24)

Contadores de janela fixa no PostgreSQL (valem com mais de uma instância), checados **antes** do Argon2, que é o custo que a recusa protege. A resposta é 429 com `Retry-After`.

| Balde | Padrão | O que conta |
|---|---|---|
| Cadastro por IP | 5 por hora | toda tentativa |
| Login por IP | 20 por 15 min | toda tentativa |
| Falhas por e-mail + IP | 5 por 15 min | só senha errada |
| Falhas por e-mail | 20 por 15 min | só senha errada, de qualquer IP |
| Pré-sessão por IP | 60 por 10 min | criação de sessão anônima |

Login correto zera os contadores do par e do e-mail. O e-mail não fica em claro na tabela (SHA-256). Tudo é configurável em `atlas.limites.*`.

Limites que seguem: **(1)** atrás de proxy o IP da conexão é o do proxy e todos dividem um balde; `ATLAS_LIMITES_CABECALHO_IP` usa o último valor do cabeçalho informado, o que só é seguro se a porta da aplicação for alcançável apenas pelo proxy. **(2)** quem controla muitos IPs consegue chegar aos 20 erros do e-mail e travar aquela conta por 15 minutos: é a troca por não permitir força bruta distribuída. **(3)** janela fixa admite até o dobro do limite na virada da janela. **(4)** upload, processamento e consultas ao OpenAlex ainda não têm limite por usuário.

### Cota de armazenamento por conta (implementada em 2026-10-24)

`atlas.cotas.bytes-por-usuario` (padrão 5 GiB) e `atlas.cotas.documentos-por-usuario` (padrão 5000). Os padrões são generosos e foram escolhidos pelo revisor, sem medição de uso nem de custo; são valores ajustáveis, não uma proibição, de acordo com o objetivo de guardar o maior número possível de documentos.

- Recusa com 413 e mensagem com o uso atual, **antes** de gravar no disco; reenviar um PDF que a conta já tem devolve o existente e não consome cota.
- A checagem definitiva roda numa transação com trava de aconselhamento por conta (`pg_advisory_xact_lock`), então dois envios simultâneos não passam juntos do limite. Teste com duas threads: um recebe 201 e o outro 413.
- `GET /api/conta/uso` mostra o uso da própria conta, exibido na tela.
- Se a gravação do arquivo ocorreu e o INSERT é recusado pela cota numa corrida, o arquivo fica no disco (pode ser compartilhado por hash com outra conta): resíduo recuperável, não vazamento.
- A cota conta bytes de PDFs originais; não conta texto extraído, medidas nem metadados no banco.

## Fase B — limites para quem é público

- Limite de taxa por usuário e por IP em upload, processamento e nas rotas que chamam o OpenAlex.
- Cota de armazenamento e de documentos por usuário (o autor quer o maior número possível de documentos; a cota é um valor ajustável, não uma proibição).
- TLS no proxy reverso; a porta do compose continua em 127.0.0.1.
- Backups do PostgreSQL e do armazenamento de documentos com restauração testada: scripts e prova de ida e volta em [backup.md](backup.md); criptografia, envio para fora da máquina e agendamento seguem sem implementar.

## Fase C — armazenamento de objetos (quando houver mais de uma instância)

- Interface `ArmazenamentoDocumentos` (`gravar`, `abrirLocal`, `existe`) com a implementação em disco atual e uma em S3/MinIO. A chave de objeto já é só o nome derivado do hash, o que torna a gravação idempotente.
- Ordem no upload: gravar o objeto, depois o registro. Objeto sem registro é lixo recuperável; registro sem objeto é erro.
- O parser precisa de arquivo local: o worker baixa para o `tmpfs` antes de iniciar o subprocesso e apaga depois.
- Teste com MinIO em Testcontainers no CI (não localmente, por memória).

## Risco de conteúdo (fato, sem juízo)

Guardar PDFs de terceiros é uma coisa; devolvê-los a outras pessoas é outra. O autor respondeu que permite compartilhamento posteriormente. A Fase A não devolve originais nem os compartilha. Permissão de armazenar/processar não deve ser tratada automaticamente como permissão de redistribuir; o contrato de compartilhamento ainda não foi implementado.

## O que continua sem medição

Memória dos contêineres sob carga, custo de hospedagem, desempenho das listagens com milhares de obras e o smoke Docker do healthcheck do worker.
