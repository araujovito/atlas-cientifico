# Revisão cruzada

Registro das revisões feitas por um segundo agente sobre o código escrito pelo primeiro. Cada entrada diz o que foi conferido, o que foi corrigido (com o commit) e o que ficou aberto. O objetivo é deixar claro o que a revisão encontrou e o que ela não cobriu.

## 2026-10-24

**Escopo conferido:** upload, processamento, registro e listagem de medidas, migrações V1 a V6, testes e CI. Os 26 testes passam localmente e o CI passou nos pushes feitos até `d47df5d`.

### Corrigido

| Achado | Commit |
|---|---|
| Dois envios simultâneos do mesmo PDF davam erro 500: capturar `DuplicateKeyException` e consultar de novo na mesma transação falha no PostgreSQL (`25P02`). Trocado por `ON CONFLICT (sha256) DO NOTHING`. | `f897ff0` |
| Verificação do nome do arquivo calculada e nunca usada; o arquivo é salvo como `<sha256>.pdf`. | `acdf443` |
| Cada medida criava um estudo e um experimento novos, então cinco medidas de um artigo viravam cinco estudos. Agora há um estudo por execução. | `95eb6f5` |
| Medidas listadas por UUID aleatório. Agora ordenadas por `registrada_em`. | `d47df5d` |
| A checagem "a execução pertence ao documento" estava repetida. | `61f2fd6` |

### Limites da revisão

- A corrida do upload foi corrigida primeiro por raciocínio. Em seguida entrou um teste com duas conexões (25 rodadas, sem transação de teste). Contra o código anterior à correção (`acdf443`) ele reproduz o erro `25P02`; com a correção passa. Como a janela da corrida é curta, passar não prova ausência de corrida, só que ela não apareceu em 25 rodadas.
- A migração V3 junta estudos e experimentos duplicados, mas só foi executada num banco vazio.
- Um teste exigia a tabela `medida` vazia e dependia da ordem de execução. Foi escopado por execução.

### Revisão dos commits seguintes (condições experimentais, V5; posição da evidência, V6; tela)

Sem defeito bloqueante. Pontos a conhecer:

- (Corrigido em `dd65493`: trecho repetido agora responde 409.) `indexOf` devolve só a **primeira** ocorrência do trecho na página. Se o mesmo trecho aparece duas vezes (por exemplo "3,4 V"), o destaque e os offsets podem apontar para o lugar errado, e a evidência mostrada nem sempre é a que a pessoa viu. O README descreve o trecho como literal, mas não diz que a posição é a primeira ocorrência.
- O trecho precisa ser literal, então um trecho que atravessa uma quebra de linha do PDF é recusado, mesmo estando no documento.
- Os offsets contam pontos de código Unicode e a tela usa `Array.from`, que também conta assim. As duas pontas combinam; o teste com emoji entrou depois.
- Evidências anteriores à V6 ficam com offsets nulos, o que está documentado.

## 2026-10-24 (continuação)

- `2ce9b38` (bloqueio de medidas quando a extração precisa de OCR): conferido. Fecha um furo real, porque marcar uma medida como `AUSENTE` num PDF sem texto afirmaria uma ausência que ninguém verificou. Tem teste.
- Chave de armazenamento relativa: novos uploads guardam só `<sha256>.pdf`, resolvido contra `ATLAS_DOCUMENTOS_DIR`. Linhas antigas com caminho absoluto continuam funcionando (`Path.resolve` mantém caminho absoluto), então não houve migração. Essas linhas antigas ainda quebram se o diretório mudar; só as novas ficam protegidas.
- Observação na tela: o `change` do seletor de página ganha um listener novo a cada envio de PDF, sem remover o anterior. É inofensivo, mas acumula.

## 2026-10-24 (revisão por quatro agentes)

Quatro revisores independentes leram o projeto cada um por uma lente (segurança, escala, dados e arquitetura, testes e operação), só com leitura. Cada achado foi conferido no código antes de virar mudança. O que cada revisor afirmou sem rodar nada está marcado como estimativa.

### Corrigido

| Achado | Commit |
|---|---|
| Faltavam índices nas chaves estrangeiras usadas em filtros e nas cascatas. Preventivo; nada foi medido. | `834b144` |
| Apagar a evidência deixava uma medida REPORTADO sem origem (o gatilho só olhava inserção da medida). | `38a157b` |
| `CHECK` do V2 aceitava upload sem confirmação se houvesse obra. | `38a157b` |
| Evidência não precisava conter o valor: valor 250 com o trecho "Introduction" era aceito como REPORTADO. | `dd65493` |
| Trecho repetido na página: a posição apontava para a primeira ocorrência sem aviso. Agora responde 409. | `dd65493` |
| Campos e condições de uma medida sem limite de tamanho. | `85533f9` |
| Mensagens de erro escritas no código não chegavam ao cliente (padrão do Spring esconde). Não coberto por teste: o MockMvc não segue para `/error`. | `85533f9` |
| Upload segurava uma conexão do pool durante o hash e a cópia do arquivo. Estimado, não medido. | `e86ce4a` |
| Sem limite de páginas; PDF com senha dava o mesmo 422 genérico; falhas sem log. O limite não impede um PDF lento, só um grande. | `01e5db6` |
| CI: actions por tag móvel, sem timeout, sem concurrency, sem relatório na falha, sem Dependabot. | `3f8e589` |
| A API só subia com Testcontainers, sem datasource nem compose; Actuator sem exposição explícita. | `95f2213` |
| README não dizia como rodar, nem os limites de segurança. | docs do README |
| Os offsets em pontos de código não tinham teste fora do plano básico. | teste de emoji |

### Conferido e não confirmado

- **V3 funde experimentos ignorando as condições.** A V3 foi escrita quando nenhum código gravava `condicoes` (sempre `{}`), então nenhum banco que aplicou V1 a V3 antes da V5 tem experimentos distintos por condição para fundir. Não foi alterada, porque editar uma migração já aplicada quebra o checksum do Flyway.
- **Chaves absolutas antigas "seguem valendo".** A frase da seção anterior só vale se o caminho antigo ainda estiver dentro de `ATLAS_DOCUMENTOS_DIR`; fora dele a verificação de caminho recusa com 500. A checagem está correta e bloqueia travessia de diretório; foi o comentário que prometeu demais.

### Decisões de produto

O autor delegou a decisão ao revisor. As tomadas e as adiadas estão em [decisoes.md](decisoes.md). A lista abaixo é a original, antes da delegação:

1. **Autenticação e dono por documento.** É o achado mais grave. Enquanto não existir, o README diz para não expor a API. Junto vem a deduplicação por hash, que devolve o registro de quem enviou antes.
2. **Exclusão de dados (LGPD).** Endpoint de remoção do PDF, das páginas e das medidas, e política de retenção. O `ON DELETE CASCADE` atual já apagaria tudo de uma vez; trocar por `RESTRICT` obrigaria o endpoint a apagar na ordem.
3. **Processamento assíncrono.** Hoje síncrono. Os estados `PENDENTE` e `EM_ANDAMENTO` existem no esquema e nunca são usados; usá-los permitiria exclusão entre instâncias e fila no Postgres. A API passaria a devolver 202.
4. **Armazenamento.** Disco local de uma instância; para várias instâncias, uma interface com implementação S3.
5. **Paginação** de páginas e medidas, que muda o formato da resposta.
6. **Medida repetida.** Não há chave natural; um reenvio cria duas medidas.
7. **Procedência.** Quem afirmou e por qual método não é registrado; qualquer chamador grava REPORTADO.
8. **Licença.** O repositório não tem arquivo LICENSE, e a escolha da licença é do autor.

### Em aberto

O trecho com quebra de linha foi corrigido em `f537bd6`. Restam as decisões adiadas em [decisoes.md](decisoes.md) e a falta de limite de tempo na extração.

### Continuação em 2026-10-24

- `d96a061` adiciona um teste ponta a ponta em Chromium para enviar um PDF sintético, registrar uma medida e navegar até o trecho destacado. O teste e a suíte completa passaram localmente (52 testes). O novo passo de instalação do navegador no GitHub Actions ainda aguarda execução remota depois do push; não foi contado como CI verde.
- Cobertura do navegador permanece limitada a Chromium e ao fluxo com texto pesquisável. Responsividade, outros navegadores e PDFs que exigem OCR não foram testados.

### Evidências da implementação seguinte — aguardando revisão cruzada

- `5fe2575`: acrescenta casos Chromium de OCR, chave incorreta e substituição de documento. Foram corrigidos o reaproveitamento de formulário/resultados antigos após novo envio e a mensagem para páginas cujo texto é apenas espaço em branco. A limitação histórica de OCR na seção anterior foi coberta por esses testes; responsividade/outros navegadores seguem abertos.
- `5693848`: consulta OpenAlex somente por DOI. Testes HTTP locais verificam mapeamento, validação, falhas, cooldown e prazo antes/depois dos cabeçalhos. O teste de corpo atrasado falhou com apenas o timeout do `HttpRequest` no Java 21 e passou com o prazo da operação completa e cancelamento.
- `9ff978b`: interface da busca, com teste de texto HTML não executado e limpeza do resultado após erro.
- Suíte completa: 86 testes passaram localmente. A API local também fez duas consultas reais com HTTP 200; o contrato e os limites estão em [openalex.md](openalex.md). Isso não substitui revisão independente nem uma nova execução do CI no GitHub.

### Nova implementação — ainda aguardando revisão independente

A implementação avançou até catálogo/coleções, fila e recuperação, parser isolado, revisão append-only, comparação, grafo de citações, operação Docker e telemetria local. O autor autorizou decisões e depois fixou orçamento zero. A sequência de commits, critérios e limites está em `handoff-codex.md` e `roteiro.md`; **esta nota não é uma revisão do Claude**.

Evidência final local: 117 testes passaram em 2026-10-24. Smoke do JAR empacotado confirmou idempotência sob oito uploads concorrentes, tarefa durável com consumidor parado, retomada e recepção de span no Jaeger. Referência científica independente, precisão de IA, carga representativa, contas/S3, AWS/CD e CI remoto permanecem sem comprovação. A extração ainda é manual; o avaliador offline e sua fixture negativa não equivalem a um modelo de extração.
