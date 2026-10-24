# Atlas Científico

Plataforma para explorar literatura científica, comparar estudos e conferir de onde vem cada informação extraída. Projeto pessoal de portfólio, em Java 21 e Spring Boot.

**Estado: fluxo local de catálogo, processamento, revisão, comparação e citações implementado e testado.** Upload autorizado, texto por página, registro manual com evidência e histórico imutável. Fila durável no PostgreSQL, trabalhador separado e parser em JVM limitada; PDFs sem texto ficam em `PRECISA_OCR`. A interface é um protótipo HTML/JS, não o frontend React/TS previsto. Extração automática por IA e OCR não estão implementados.

O catálogo persiste identificadores e snapshots de metadados sem sobrescrita silenciosa, oferece descoberta por tema/autor/ano e coleções compartilhadas do operador. Referências do OpenAlex alimentam grafo paginado e linha do tempo; dados ausentes não são inventados. A comparação preserva originais e só alinha numericamente quando unidades e condições declaradas satisfazem regras explícitas. Isso não prova equivalência científica.

**Orçamento definido pelo autor: R$ 0.** Nenhum recurso AWS foi criado. Operação local em Docker e avaliação offline não usam chamadas pagas. Estado detalhado, evidências e pendências: [roteiro](docs/roteiro.md), [operação](docs/operacao.md), [avaliação](docs/avaliacao.md).

## Ideia

Ligar *artigo → experimento → resultado → trecho de evidência*. Cada valor numa tabela de comparação aponta para a página e o trecho de origem, mantém o valor e a unidade originais e diz se foi reportado, inferido, ausente ou se aguarda revisão.

## Limites

- Usa metadados do OpenAlex e PDFs que a pessoa declara poder processar. Unpaywall e Europe PMC seguem como conectores futuros; não há download automático de PDFs.
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
Os testes do conector usam uma fonte HTTP local; os testes da busca no navegador usam respostas controladas da API. A suíte não depende da disponibilidade do OpenAlex público.

Última execução local em 2026-10-24: **117 testes, sem falhas, erros ou testes ignorados**. Smoke Docker separado também passou, incluindo span real recebido no Jaeger. CI remoto destes commits ainda aguarda revisão/push.

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
- **O processamento da tela é assíncrono**, com fila durável e recuperação por reserva expirada. O endpoint síncrono antigo continua por compatibilidade. O parser tem prazo padrão de 20 s, heap de 256 MiB e limite de páginas; não é uma sandbox de segurança de sistema operacional. O armazenamento continua em volume local; não há S3 nem suporte a volumes independentes em múltiplos hosts.
- **A revisão identifica autor declarado**, não identidade autenticada. Histórico é append-only, mas não substitui validação científica independente.

A revisão que originou esta lista está em [docs/revisao-cruzada.md](docs/revisao-cruzada.md), e as decisões tomadas e adiadas, com o motivo, em [docs/decisoes.md](docs/decisoes.md).

## Busca por DOI

Na tela inicial, use **Buscar um artigo por DOI** com um identificador como `10.1038/s41467-022-28959-5` ou seu link `https://doi.org/...`. O resultado identifica a fonte dos metadados e mostra links para o DOI e para o OpenAlex. Nenhum PDF é baixado nem autorização de uso do texto completo é inferida.

```bash
curl --get --data-urlencode 'doi=10.1038/s41467-022-28959-5' \
  http://localhost:8080/api/catalogo/obras/por-doi
```

Com a chave compartilhada da API habilitada, a consulta também exige `X-Atlas-Chave`. Uma chave opcional do provedor pode ser configurada em `OPENALEX_API_KEY`, somente no servidor. O conector limita as consultas por instância e tem prazo HTTP padrão de 5 segundos, incluindo o corpo da resposta. Configuração, tratamento de erros, verificações reais e limites estão em [docs/openalex.md](docs/openalex.md).

## Upload mínimo

Com a API em execução, abra `http://localhost:8080/` para usar a tela mínima: enviar um PDF autorizado, processar o texto e registrar ou consultar medidas com evidência. O registro de medidas é manual; a interface não afirma que houve extração automática.

`POST /api/documentos` recebe `multipart/form-data` com `arquivo` (PDF) e `autorizacaoConfirmada=true`; `titulo` é opcional e declarado pela pessoa, não inferido pelo sistema. O limite padrão é 20 MiB e pode ser ajustado com `ATLAS_DOCUMENTOS_TAMANHO_MAXIMO`. Os arquivos são armazenados em `data/documentos`, relativo ao diretório em que a API foi iniciada (`ATLAS_DOCUMENTOS_DIR` para mudar o diretório). A resposta indica `duplicado` quando o mesmo conteúdo já foi recebido.

Este endpoint só registra e armazena o PDF. Ainda não extrai texto, não confirma licença e não produz resultados científicos.

`POST /api/documentos/{id}/extracoes` executa a extração síncrona com PDFBox, grava o texto separado por página e retorna o estado e a quantidade de páginas. A mesma combinação de PDF e versão do processador reutiliza a execução já gravada. PDFs sem texto pesquisável ficam com `PRECISA_OCR`; OCR ainda não está implementado.

O fluxo preferido é `POST /api/documentos/{id}/processamentos` (202 e Location), seguido de `GET /api/processamentos/{tarefa}`. A tela acompanha a tarefa e permite retomá-la após recarregar. Retentativas limitadas, eventos, reativação explícita e instruções dos contêineres separados estão em [operação](docs/operacao.md).

`GET /api/documentos/{documentoId}/extracoes/{execucaoId}/paginas` permite conferir o texto extraído, com o número de cada página. A execução precisa pertencer ao documento informado; caso contrário, a API retorna `404`.

`POST /api/documentos/{documentoId}/extracoes/{execucaoId}/medidas` registra uma medida candidata sem chamar IA. Para estados diferentes de `AUSENTE`, a página e um trecho de evidência são obrigatórios. A conferência tolera diferenças de espaços e quebras de linha, mas não diferenças de caracteres; se a citação corresponder a mais de um lugar da página, a API pede um trecho mais específico. A evidência guardada é o trecho original da página, com offsets quando disponíveis. Para estado `REPORTADO`, o valor também precisa aparecer no trecho. Valor, unidade originais e condições experimentais são preservados. Experimentos do mesmo material com condições diferentes ficam separados. O endpoint é um alicerce para avaliar extrações pré-gravadas, não uma extração automática.

`GET /api/documentos/{documentoId}/extracoes/{execucaoId}/medidas` lista as medidas da execução com objeto, métrica, valor e unidade originais, estado, condições experimentais e evidência (página e trecho). Medidas no estado `AUSENTE` são retornadas sem evidência.

## Documentos

- [Especificação inicial](docs/especificacao.md): recorte, arquitetura, modelo de dados, eventos, decisões e entregas.
- [Decisões](docs/decisoes.md): o que foi escolhido, adiado e por quê.
- [Revisão cruzada](docs/revisao-cruzada.md): achados da revisão por um segundo agente, o que foi corrigido e o que segue aberto.
