# Contas, armazenamento e hospedagem pública: plano para o Codex

Nota do revisor (2026-10-24). Nada deste documento está implementado. Ele existe porque `decisoes.md` adia estes itens "até hospedar ou dar acesso a mais de uma pessoa" e a intenção declarada do autor é hospedar o site. Este plano diz **o que** precisa existir e **como cada passo é verificado**; a implementação continua sendo do Codex.

## Ponto de partida (fatos)

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
6. **Cadastro.** Por convite ou lista de permissões no início: cadastro aberto sem limite convida abuso de armazenamento e de consultas ao OpenAlex.

Critério de conclusão: suíte com os testes de IDOR verdes no CI, migração testada sobre um banco com dados antigos (a V9 e a V12 só foram exercitadas em banco vazio ou quase), e a chave compartilhada removida ou reduzida a segredo de operação.

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

Guardar PDFs de terceiros é uma coisa; devolvê-los a outras pessoas é outra. Se o arquivo original só for devolvido ao próprio dono, o sistema não redistribui o artigo. O autor decide esse comportamento; o plano acima assume "somente o dono" até ele dizer o contrário.

## O que continua sem medição

Memória dos contêineres sob carga, custo de hospedagem, desempenho das listagens com milhares de obras e o smoke Docker do healthcheck do worker.
