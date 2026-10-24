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
O teste de interface abre Chromium com Playwright. Na primeira execução local, instale o navegador compatível pelo wrapper (o `SKIP_BROWSER_GC` evita remover versões usadas por outros projetos):

```bash
cd api
PLAYWRIGHT_SKIP_BROWSER_GC=1 ./mvnw exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
```

No GitHub Actions, o workflow instala Chromium e suas dependências antes de executar a mesma suíte em pushes e pull requests para `main`.

## Executar a API

```bash
docker compose up -d        # PostgreSQL 18 em 127.0.0.1:5433
cd api
./mvnw spring-boot:run      # http://localhost:8080
```

As variáveis `ATLAS_DB_URL`, `ATLAS_DB_USER` e `ATLAS_DB_PASSWORD` mudam a conexão; os padrões servem só para a máquina local. `ATLAS_DOCUMENTOS_DIR` define onde os PDFs ficam (padrão `./data/documentos`) e `atlas.documentos.paginas-maximas` limita as páginas por PDF (padrão 500). `GET /actuator/health` é o único endpoint operacional exposto.

## Limites conhecidos de segurança e de escala

Este é um projeto de portfólio em desenvolvimento, e os pontos abaixo não estão resolvidos:

- **Não há contas de usuário nem dono por documento.** Existe uma chave compartilhada: com `ATLAS_CHAVE_API` definida, `/api/**` exige o cabeçalho `X-Atlas-Chave` (a tela tem um campo para ela). Quem tem a chave vê todos os documentos, e enviar um PDF já existente devolve o registro do envio anterior. Sem a variável a API fica aberta, o que serve só para a máquina local.
- **A autorização para processar é uma declaração**, uma caixa marcada, sem identidade de quem declarou. Registra a afirmação, não a comprova.
- **Os PDFs e o texto extraído ficam sem criptografia**, em disco e no banco, e não há endpoint de exclusão nem política de retenção.
- **A extração é síncrona**, dentro da requisição, com limite de páginas mas sem limite de tempo; o armazenamento é o disco local de uma instância. A lista de medidas é paginada (`limite`, `deslocamento`).

A revisão que originou esta lista está em [docs/revisao-cruzada.md](docs/revisao-cruzada.md), e as decisões tomadas e adiadas, com o motivo, em [docs/decisoes.md](docs/decisoes.md).

## Upload mínimo

Com a API em execução, abra `http://localhost:8080/` para usar a tela mínima: enviar um PDF autorizado, processar o texto e registrar ou consultar medidas com evidência. O registro de medidas é manual; a interface não afirma que houve extração automática.

`POST /api/documentos` recebe `multipart/form-data` com `arquivo` (PDF) e `autorizacaoConfirmada=true`; `titulo` é opcional e declarado pela pessoa, não inferido pelo sistema. O limite padrão é 20 MiB e pode ser ajustado com `ATLAS_DOCUMENTOS_TAMANHO_MAXIMO`. Os arquivos são armazenados em `data/documentos`, relativo ao diretório em que a API foi iniciada (`ATLAS_DOCUMENTOS_DIR` para mudar o diretório). A resposta indica `duplicado` quando o mesmo conteúdo já foi recebido.

Este endpoint só registra e armazena o PDF. Ainda não extrai texto, não confirma licença e não produz resultados científicos.

`POST /api/documentos/{id}/extracoes` executa a extração síncrona com PDFBox, grava o texto separado por página e retorna o estado e a quantidade de páginas. A mesma combinação de PDF e versão do processador reutiliza a execução já gravada. PDFs sem texto pesquisável ficam com `PRECISA_OCR`; OCR ainda não está implementado.

`GET /api/documentos/{documentoId}/extracoes/{execucaoId}/paginas` permite conferir o texto extraído, com o número de cada página. A execução precisa pertencer ao documento informado; caso contrário, a API retorna `404`.

`POST /api/documentos/{documentoId}/extracoes/{execucaoId}/medidas` registra uma medida candidata sem chamar IA. Para estados diferentes de `AUSENTE`, a página e um trecho de evidência são obrigatórios. A conferência tolera diferenças de espaços e quebras de linha, mas não diferenças de caracteres; se a citação corresponder a mais de um lugar da página, a API pede um trecho mais específico. A evidência guardada é o trecho original da página, com offsets quando disponíveis. Para estado `REPORTADO`, o valor também precisa aparecer no trecho. Valor, unidade originais e condições experimentais são preservados. Experimentos do mesmo material com condições diferentes ficam separados. O endpoint é um alicerce para avaliar extrações pré-gravadas, não uma extração automática.

`GET /api/documentos/{documentoId}/extracoes/{execucaoId}/medidas` lista as medidas da execução com objeto, métrica, valor e unidade originais, estado, condições experimentais e evidência (página e trecho). Medidas no estado `AUSENTE` são retornadas sem evidência.

## Documentos

- [Especificação inicial](docs/especificacao.md): recorte, arquitetura, modelo de dados, eventos, decisões e entregas.
- [Decisões](docs/decisoes.md): o que foi escolhido, adiado e por quê.
- [Revisão cruzada](docs/revisao-cruzada.md): achados da revisão por um segundo agente, o que foi corrigido e o que segue aberto.
