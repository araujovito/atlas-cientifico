# Decisões

Registro curto de decisões de projeto: o que foi escolhido, o porquê e o que ficou de fora. Cada entrada tem a data e o commit. Decisões tomadas pelo revisor por delegação do autor estão marcadas.

## Tomadas em 2026-10-24 (revisor, por delegação do autor)

| Decisão | Motivo | Limite | Commit |
|---|---|---|---|
| Licença MIT | Repositório público sem licença significa todos os direitos reservados. MIT é curta, permissiva e usual em portfólio. | Pode mudar enquanto o autor for o único colaborador. | `a9d83dd` |
| Chave de acesso compartilhada em `/api/**` (cabeçalho `X-Atlas-Chave`, ligada por `ATLAS_CHAVE_API`) | Era o achado mais grave: qualquer pessoa na rede lia e gravava tudo. Contas e dono por documento são maiores; a chave é o menor controle que impede uma instância exposta de ficar aberta. | **Não é autenticação de usuários.** Quem tem a chave vê tudo, e o upload duplicado ainda revela que o arquivo já existia. | `38731f2` |
| Medida idempotente: identificada por experimento, métrica, estado, valor e unidade; a mesma medida citada em outro lugar ganha outra evidência | Um reenvio do cliente criava duas medidas. O estado entra na chave para um registro não sobrescrever a afirmação de outro. | A listagem devolve uma linha por evidência. | `b11f1e8` |
| Trecho tolerante a espaço em branco, guardado como está na página | O PDF quebra linhas no meio de frases; exigir igualdade byte a byte recusava citações corretas. | Hifenização, maiúsculas e caracteres diferentes continuam recusados. | `f537bd6` |
| Trecho repetido na página responde 409 | A posição apontaria para uma ocorrência que pode não ser a citada. | A pessoa precisa ampliar o trecho. | `dd65493` |
| Valor de medida `REPORTADO` precisa estar no trecho | A evidência tinha de conter o que sustenta. | `INFERIDO` e `PENDENTE_REVISAO` podem apontar contexto. | `dd65493` |
| Erros em RFC 9457 (`application/problem+json`) | A tela lê `detail`; sem isso toda recusa aparecia como "falhou (422)". | — | `e16ffd0` |
| Listagem de medidas paginada (`limite` até 500, `deslocamento`) | Devolvia tudo em memória. A resposta continua sendo um array. | Paginação por deslocamento; não medida. | `795573b` |
| Limite de 500 páginas por PDF | Extração síncrona e sem teto era uma porta para derrubar a API. | Limita PDFs grandes, não lentos; o valor 500 é estimativa. | `01e5db6` |
| CodeQL no CI | Faltava análise estática de segurança. | A primeira execução não abriu alertas; isso não prova ausência de falhas. | `729bc9c` |

## Adiadas, com a razão

| Tema | Decisão | Quando revisitar |
|---|---|---|
| Contas de usuário e dono por documento | Adiada. A chave compartilhada cobre o período em que há um único operador. | Antes de dar acesso a mais de uma pessoa ou de hospedar publicamente. Plano passo a passo em [contas-e-hospedagem.md](contas-e-hospedagem.md). |
| Processamento assíncrono | Adiado para a entrega 3 da especificação. O caminho é uma fila no PostgreSQL (`FOR UPDATE SKIP LOCKED`) usando os estados `PENDENTE` e `EM_ANDAMENTO`, que já existem no esquema; a API passa a devolver 202. | Quando houver PDFs grandes demais para a requisição ou mais de uma instância. |
| Armazenamento em S3 | Adiado. A chave de armazenamento já é só o nome do arquivo, então cabe como chave de objeto atrás de uma interface. | Quando houver mais de uma instância. |
| Procedência de quem afirmou (`REPORTADO`, método, versão do extrator) | Adiada: exige identidade de quem registra. | Junto com as contas. |
| Exclusão de dados e retenção | **Decisão do autor:** o projeto não pretende seguir a LGPD. O README descreve o que o sistema faz hoje, sem prometer conformidade. | — |
| Medidas fora do plano básico de texto, hifenização, OCR | Fora do escopo da entrega 1. | Entrega seguinte. |

## O que nada disso mede

Desempenho, uso de memória e precisão de extração **não foram medidos**. Os números de limite (500 páginas, 500 itens por página da listagem, 200 por padrão) são escolhas razoáveis, não resultado de medição.

## Início da entrega 2 em 2026-10-24 (implementação, por delegação do autor)

| Decisão | Motivo | Limite | Commit |
|---|---|---|---|
| Começar por consulta de metadados por DOI, sem persistir | Valida o contrato da fonte e entrega um resultado utilizável antes de definir fusão de metadados de fontes diferentes. O resultado mantém os identificadores para a próxima etapa. | Deduplicação do catálogo, busca por tema/autor e coleções continuam planejadas. | `5693848`, `9ff978b` |
| Prazo HTTP padrão de 5 s, intervalo mínimo de 200 ms por instância e espera em 429 | Evita prender a requisição indefinidamente ou repetir chamadas contra um limite do provedor. O teste de corpo atrasado refutou a suposição de que o timeout padrão da requisição cobria tudo. | Valores preventivos, sem medição; o controle de taxa é local à instância. | `5693848` |
| Limpar o contexto anterior ao trocar PDF e bloquear troca durante gravação | O formulário e o texto antigos podiam continuar visíveis após falha no segundo envio. Isso dificultava conferir qual documento estava ativo. | Coberto em Chromium; outros navegadores não foram verificados. | `5fe2575` |

Fontes oficiais e evidências das chamadas reais estão em [openalex.md](openalex.md). Esses testes não medem precisão dos metadados nem desempenho.

## Continuação autorizada pelo autor — 2026-10-24

O autor autorizou executar o roteiro e tomar decisões com boas práticas, bom senso e questionamento das hipóteses. Isso libera a entrega 3 antes adiada. Posteriormente definiu **nenhum gasto**: AWS, serviços pagos e chamadas pagas de modelos não serão utilizados. As decisões históricas acima ficam registradas; os estados atuais são os seguintes.

| Decisão | Por quê | Hipótese questionada e limite |
|---|---|---|
| Fila no PostgreSQL, sem Kafka | Inserir comando e evento na mesma transação elimina a janela de gravação/publicação; o UPDATE condicional da reserva (com token) permite consumidores concorrentes sem outro serviço | Não há necessidade demonstrada de Kafka. Reserva com prazo e token evita confirmação antiga; o teste interrompe após persistência e comprova reaproveitamento. Não promete exatamente uma vez nem substitui medição de escala |
| API e worker usam o mesmo JAR em processos distintos | Evita duplicar aplicação e dependências sem perder separação operacional | O smoke executou o JAR real com parser em outra JVM. Um host/volume compartilhado ainda é requisito; não é arquitetura multi-host |
| Parser em JVM filha com prazo/heap | O limite de páginas não controla PDF lento ou explosão de memória dentro da API | Teste usa processo deliberadamente lento e verifica morte do PID. Ambiente filho não herda segredos. Ainda lê o volume permitido e não é sandbox de SO; PDFs hostis exigem defesa adicional |
| Catálogo preserva snapshots e não faz fusão silenciosa | DOI e OpenAlex ID podem discordar; título de fonte não é identidade suficiente | Locks ordenados e constraints protegem concorrência. Conflito explícito 409. Segunda fonte foi simulada no teste, não integrada |
| Coleções compartilhadas por operador | Mantém o modelo atual de chave compartilhada, sem fingir contas individuais | Antes de acesso público/múltiplos usuários, implementar propriedade/autorização por recurso; não hospedar assim na internet |
| Revisões append-only, com versão otimista | Corrigir uma leitura não pode apagar a evidência anterior; dois revisores não devem sobrescrever um ao outro | Banco impede UPDATE/DELETE; autor é declarado. Não equivale a assinatura ou revisão científica independente |
| Conversões limitadas e BigDecimal | Cobrir poucas unidades conhecidas com testes é verificável; originais nunca mudam | Mesmas condições declaradas permitem alinhamento técnico, não prova de comparabilidade científica. Condições ausentes bloqueiam alinhamento; nenhum ranking é produzido |
| Grafo com expansão solicitada e páginas pequenas | Evita fanout automático contra provedor e registros inferidos sem base | Cada ligação vem de snapshot OpenAlex. Título/ano desconhecidos continuam nulos. Fonte bibliométrica não comprova qualidade |
| Manter protótipo HTML/JS nesta rodada | Os fluxos solicitados foram demonstrados sem introduzir build/frontend novo | React/TS continua pendente. Não chamar o protótipo de frontend final; revisar a decisão se estado/complexidade exigir |
| Telemetria local opt-in | Atender observabilidade sem enviar dados para fornecedor nem depender de orçamento | Métricas são por processo; tracing não transporta automaticamente contexto pela fila durável. Logs/métricas/traços externos desligados por padrão |
| Avaliação offline antes de modelo pago | É necessário saber detectar erro de associação/evidência antes de otimizar prompt | Fixture negativa testa o avaliador, não a precisão de um extrator. Conjunto científico precisa de revisão humana independente; sem números inventados |

Os tempos operacionais agora têm uma amostra sintética, explicitamente limitada, em `operacao.md`. Isso não altera a ausência de medição de precisão científica e carga representativa. O revisor Claude ainda não revisou estes novos commits nem fez seu push.
