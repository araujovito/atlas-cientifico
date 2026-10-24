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
| Listagem de medidas paginada (`limite` até 500, `deslocamento`) | Devolvia tudo em memória. A resposta continua sendo um array. | Paginação por deslocamento; não medida. | paginação |
| Limite de 500 páginas por PDF | Extração síncrona e sem teto era uma porta para derrubar a API. | Limita PDFs grandes, não lentos; o valor 500 é estimativa. | `01e5db6` |
| CodeQL no CI | Faltava análise estática de segurança. | A primeira execução não abriu alertas; isso não prova ausência de falhas. | `codeql.yml` |

## Adiadas, com a razão

| Tema | Decisão | Quando revisitar |
|---|---|---|
| Contas de usuário e dono por documento | Adiada. A chave compartilhada cobre o período em que há um único operador. | Antes de dar acesso a mais de uma pessoa ou de hospedar publicamente. |
| Processamento assíncrono | Adiado para a entrega 3 da especificação. O caminho é uma fila no PostgreSQL (`FOR UPDATE SKIP LOCKED`) usando os estados `PENDENTE` e `EM_ANDAMENTO`, que já existem no esquema; a API passa a devolver 202. | Quando houver PDFs grandes demais para a requisição ou mais de uma instância. |
| Armazenamento em S3 | Adiado. A chave de armazenamento já é só o nome do arquivo, então cabe como chave de objeto atrás de uma interface. | Quando houver mais de uma instância. |
| Procedência de quem afirmou (`REPORTADO`, método, versão do extrator) | Adiada: exige identidade de quem registra. | Junto com as contas. |
| Exclusão de dados e retenção | **Decisão do autor:** o projeto não pretende seguir a LGPD. O README descreve o que o sistema faz hoje, sem prometer conformidade. | — |
| Medidas fora do plano básico de texto, hifenização, OCR | Fora do escopo da entrega 1. | Entrega seguinte. |

## O que nada disso mede

Desempenho, uso de memória e precisão de extração **não foram medidos**. Os números de limite (500 páginas, 500 itens por página da listagem, 200 por padrão) são escolhas razoáveis, não resultado de medição.
