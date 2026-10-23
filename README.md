# Atlas Científico

Plataforma para explorar literatura científica, comparar estudos e conferir de onde vem cada informação extraída. Projeto pessoal de portfólio, em Java 21 e Spring Boot.

**Estado: planejamento.** Ainda não há código. Tudo o que está descrito na especificação consta como *planejado*; nada está implementado, testado ou medido.

## Ideia

Ligar *artigo → experimento → resultado → trecho de evidência*. Cada valor numa tabela de comparação aponta para a página e o trecho de origem, mantém o valor e a unidade originais e diz se foi reportado, inferido, ausente ou se aguarda revisão.

## Limites

- Usa apenas fontes abertas e documentos que a pessoa tem autorização para processar (OpenAlex, Unpaywall, Europe PMC, PDFs enviados).
- Não integra o Sci-Hub nem contorna controle de acesso. O motivo está na seção 3 da especificação.
- Não afirma verdade científica nem contradição entre estudos só porque os números diferem.

## Documentos

- [Especificação inicial](docs/especificacao.md): recorte, arquitetura, modelo de dados, eventos, decisões e entregas.
