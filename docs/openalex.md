# Consulta de metadados por DOI

Primeiro passo da entrega 2, implementado em `5693848` (conector/API) e `9ff978b` (tela). A consulta é somente leitura: não grava `obra`/`identificador`, não associa uploads existentes e não baixa o texto completo.

## Contrato do Atlas

`GET /api/catalogo/obras/por-doi?doi=10.1038/s41467-022-28959-5`

Aceita DOI puro, prefixo `doi:` ou link HTTP(S) de `doi.org`/`dx.doi.org`. Remove espaços das extremidades e normaliza maiúsculas; espaços internos são recusados. Não abre uma URL fornecida pelo visitante. O DOI é codificado no caminho de uma origem configurada no servidor.

Resposta de exemplo, conferida pela API local contra o OpenAlex público em 2026-10-24:

```json
{
  "fonte": "OPENALEX",
  "openAlexId": "https://openalex.org/W4220682882",
  "doi": "10.1038/s41467-022-28959-5",
  "titulo": "A dual-function liquid electrolyte additive for high-energy non-aqueous lithium metal batteries",
  "ano": 2022,
  "autores": ["Yuji Zhang", "Wu Yuan", "Huiyi Li", "Jinghao Chen", "Danni Lei", "Chengxin Wang"]
}
```

Os nomes são os retornados pela fonte, sem correção ou verificação editorial pelo Atlas. Título e ano podem ser nulos; autores ausentes retornam uma lista vazia. DOI incompatível com o solicitado ou identificador OpenAlex inválido são recusados.

| HTTP do Atlas | Situação |
|---|---|
| 400 | Parâmetro ausente ou DOI inválido |
| 401 | Chave compartilhada do Atlas ausente/incorreta, quando habilitada |
| 404 | OpenAlex não encontrou a obra |
| 429 | Intervalo local entre consultas/cooldown ainda não terminou |
| 502 | Falha de rede, resposta malformada, redirecionamento ou outro erro do provedor |
| 503 | Provedor limitou/recusou o acesso ou a operação foi interrompida |
| 504 | Prazo HTTP excedido, inclusive durante a leitura do corpo |

Erros usam `application/problem+json`, com uma mensagem em `detail`. A tela limpa o resultado anterior quando uma nova consulta começa ou falha.

## Integração e configuração

Contrato primário consultado em 2026-10-24: [entidade por DOI](https://help.openalex.org/api/get-single-entities/), [autenticação e limites](https://help.openalex.org/api/authentication/) e [visão geral da API](https://help.openalex.org/api/). Usa `GET /works/doi:{doi}` com `select=id,doi,title,publication_year,authorships`.

- `OPENALEX_API_KEY`: opcional, enviada como Bearer somente pelo servidor. A chave não aparece em query string nem no navegador.
- `atlas.fontes.openalex.url-base`: padrão `https://api.openalex.org`. É configuração do operador; os testes apontam para uma fonte HTTP local.
- `atlas.fontes.openalex.tempo-limite`: padrão `5s`, positivo e de até um minuto. O conector limita a conexão a 2 segundos e a operação HTTP completa ao prazo configurado. Cancelamento por prazo também cancela a chamada assíncrona.
- Há um intervalo mínimo de 200 ms entre inícios de consulta por instância (até 5/s). É uma escolha preventiva abaixo do limite anônimo documentado de 10/s, não uma medição. O controle não coordena várias instâncias nem outros programas que usem a mesma chave/IP.
- Em `429` do provedor, não há repetição automática. O conector respeita `Retry-After` numérico (até 24 horas) ou uma data HTTP futura; sem valor utilizável, aguarda 60 segundos. Chamadas durante a espera são recusadas localmente.
- Redirecionamentos não são seguidos. O suporte a registros fundidos que exijam redirecionar ainda precisa ser implementado com validação de destino.

A fonte identifica seus metadados como CC0. Isso não transfere a licença para os PDFs referenciados.

## Verificação e limites

- `OpenAlexClientTest`: servidor HTTP local para normalização, codificação de URL, campos ausentes, credenciais, respostas inconsistentes, 404/429/5xx, cooldown e resposta lenta antes/depois dos cabeçalhos. O teste de corpo lento falhou com apenas `HttpRequest.timeout` no Java 21 e passou após aplicar prazo à operação completa.
- `CatalogoControllerTest`: contrato JSON e parâmetro obrigatório.
- `InterfaceNavegadorTest`: exibição de metadados com respostas controladas; texto externo com marcação HTML é mostrado como texto, e uma falha oculta o resultado anterior.
- Suíte completa local em 2026-10-24: 86 testes, sem falhas. CI remoto desta mudança ainda depende do push/revisão.
- Verificação manual adicional: API local consultou o OpenAlex real para `10.1038/s41467-022-28959-5` e `10.1038/s41597-022-01217-5`, ambos com HTTP 200 e IDs `W4220682882`/`W4220906497`. DOI inválido respondeu 400 com `detail`. As chamadas reais não fazem parte do CI e não constituem medição de qualidade ou disponibilidade do provedor.

Persistência, deduplicação entre fontes, busca por tema/autor e coleções continuam planejadas. A precisão dos metadados não foi avaliada.
