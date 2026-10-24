# Atlas Científico

Plataforma para explorar literatura científica, comparar estudos e conferir de onde vem cada informação extraída. Projeto pessoal de portfólio, em Java 21 e Spring Boot.

**Estado: primeira fatia em implementação.** A API Spring Boot e a migração inicial estão testadas. O endpoint de upload de PDF valida assinatura e tamanho, exige confirmação de autorização, calcula SHA-256 e evita duplicatas. Extração de texto, processamento e interface de evidências ainda estão planejados.

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

## Upload mínimo

`POST /api/documentos` recebe `multipart/form-data` com `arquivo` (PDF) e `autorizacaoConfirmada=true`; `titulo` é opcional e declarado pela pessoa, não inferido pelo sistema. O limite padrão é 20 MiB e pode ser ajustado com `ATLAS_DOCUMENTOS_TAMANHO_MAXIMO`. Os arquivos são armazenados em `data/documentos`, relativo ao diretório em que a API foi iniciada (`ATLAS_DOCUMENTOS_DIR` para mudar o diretório). A resposta indica `duplicado` quando o mesmo conteúdo já foi recebido.

Este endpoint só registra e armazena o PDF. Ainda não extrai texto, não confirma licença e não produz resultados científicos.

## Documentos

- [Especificação inicial](docs/especificacao.md): recorte, arquitetura, modelo de dados, eventos, decisões e entregas.
