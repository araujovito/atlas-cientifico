# Atlas Científico

Plataforma para explorar literatura científica, comparar estudos e conferir de onde vem cada informação extraída. Projeto pessoal de portfólio, em Java 21 e Spring Boot.

**Estado: entrega 1 em andamento.** A API Spring Boot, o upload autorizado, a extração textual por página, o registro manual de medidas com evidência literal e uma tela mínima estão implementados e testados. Cada execução é idempotente por hash do documento e versão do processador; PDFs sem camada de texto ficam com estado `PRECISA_OCR`. Extração automática, OCR e interface completa continuam planejados.

## Ideia

Ligar *artigo → experimento → resultado → trecho de evidência*. Cada valor numa tabela de comparação aponta para a página e o trecho de origem, mantém o valor e a unidade originais e diz se foi reportado, inferido, ausente ou se aguarda revisão.

## Limites

- Usa apenas fontes abertas e documentos que a pessoa tem autorização para processar (OpenAlex, Unpaywall, Europe PMC, PDFs enviados).
- Não integra o Sci-Hub nem contorna controle de acesso. O motivo está na seção 3 da especificação.
- Não afirma verdade científica nem contradição entre estudos só porque os números diferem.

## Executar testes

```bash
cd api
./mvnw test
```

Os testes de integração usam Testcontainers e precisam de Docker.
O GitHub Actions executa essa mesma suíte em pushes e pull requests para `main`.

## Upload mínimo

Com a API em execução, abra `http://localhost:8080/` para usar a tela mínima: enviar um PDF autorizado, processar o texto e registrar ou consultar medidas com evidência. O registro de medidas é manual; a interface não afirma que houve extração automática.

`POST /api/documentos` recebe `multipart/form-data` com `arquivo` (PDF) e `autorizacaoConfirmada=true`; `titulo` é opcional e declarado pela pessoa, não inferido pelo sistema. O limite padrão é 20 MiB e pode ser ajustado com `ATLAS_DOCUMENTOS_TAMANHO_MAXIMO`. Os arquivos são armazenados em `data/documentos`, relativo ao diretório em que a API foi iniciada (`ATLAS_DOCUMENTOS_DIR` para mudar o diretório). A resposta indica `duplicado` quando o mesmo conteúdo já foi recebido.

Este endpoint só registra e armazena o PDF. Ainda não extrai texto, não confirma licença e não produz resultados científicos.

`POST /api/documentos/{id}/extracoes` executa a extração síncrona com PDFBox, grava o texto separado por página e retorna o estado e a quantidade de páginas. A mesma combinação de PDF e versão do processador reutiliza a execução já gravada. PDFs sem texto pesquisável ficam com `PRECISA_OCR`; OCR ainda não está implementado.

`GET /api/documentos/{documentoId}/extracoes/{execucaoId}/paginas` permite conferir o texto extraído, com o número de cada página. A execução precisa pertencer ao documento informado; caso contrário, a API retorna `404`.

`POST /api/documentos/{documentoId}/extracoes/{execucaoId}/medidas` registra uma medida candidata sem chamar IA. Para estados diferentes de `AUSENTE`, a página e o trecho são obrigatórios, e o trecho precisa existir literalmente no texto extraído daquela página. Valor, unidade originais e condições experimentais são preservados. Experimentos do mesmo material com condições diferentes ficam separados. O endpoint é um alicerce para avaliar extrações pré-gravadas, não uma extração automática.

`GET /api/documentos/{documentoId}/extracoes/{execucaoId}/medidas` lista as medidas da execução com objeto, métrica, valor e unidade originais, estado, condições experimentais e evidência (página e trecho). Medidas no estado `AUSENTE` são retornadas sem evidência.

## Documentos

- [Especificação inicial](docs/especificacao.md): recorte, arquitetura, modelo de dados, eventos, decisões e entregas.
- [Revisão cruzada](docs/revisao-cruzada.md): achados da revisão por um segundo agente, o que foi corrigido e o que segue aberto.
