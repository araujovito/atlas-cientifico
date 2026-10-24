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

- `indexOf` devolve só a **primeira** ocorrência do trecho na página. Se o mesmo trecho aparece duas vezes (por exemplo "3,4 V"), o destaque e os offsets podem apontar para o lugar errado, e a evidência mostrada nem sempre é a que a pessoa viu. O README descreve o trecho como literal, mas não diz que a posição é a primeira ocorrência.
- O trecho precisa ser literal, então um trecho que atravessa uma quebra de linha do PDF é recusado, mesmo estando no documento.
- Os offsets contam pontos de código Unicode e a tela usa `Array.from`, que também conta assim. As duas pontas combinam; vale manter um teste com emoji ou caractere fora do plano básico.
- Evidências anteriores à V6 ficam com offsets nulos, o que está documentado.

### Em aberto

1. `chave_armazenamento` guarda caminho absoluto; mudar `ATLAS_DOCUMENTOS_DIR` quebra o processamento de documentos já enviados (erro 500).
2. O `CHECK upload_com_autorizacao_confirmada` do V2 aceita `obra_id IS NOT NULL` no lugar da confirmação, então protege menos do que o comentário diz.
3. Trecho literal com quebra de linha (ver acima).
4. Offsets de trechos repetidos na página (ver acima).
