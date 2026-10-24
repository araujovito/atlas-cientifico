# Avaliação reproduzível, sem nota de outro modelo

## O que existe

`AvaliadorCampos` compara saídas já produzidas com anotações de referência. A chave é `(SHA-256 do PDF, experimento, métrica)`: trocar de experimento não pode ser mascarado por uma ordenação conveniente. Referência e saída não aceitam chaves duplicadas. Uma omissão não equivale ao estado explícito `AUSENTE`, que não aceita valor ou evidência inventados.

O relatório conta separadamente estado, valor original, unidade original, página+trecho e condições exatas. Um campo só é completamente correto quando todos correspondem. Precisão exata = campos completamente corretos / candidatos; cobertura exata = campos completamente corretos / referência. Denominador zero produz `null`, não 100%. Essas métricas não são avaliação de verdade científica ou equivalência semântica; `125` e `125.0` são originais diferentes. A comparação de unidades normalizadas é testada em outro módulo e não altera essa avaliação de fidelidade.

```bash
cd api
./mvnw test-compile exec:java -Dexec.mainClass=dev.atlas.avaliacao.AvaliacaoMain \
  -Dexec.args=../docs/fixtures/avaliacao-sintetica.json
```

A fixture fornecida contém propositalmente uma página errada e uma ausência explícita correta. Ela não é saída de IA nem artigo real. O relatório precisa mostrar somente um de dois campos inteiramente correto, embora os dois valores originais correspondam. Quatro testes cobrem permutação, experimento errado, evidência errada, omissão, ausência, duplicata e número original diferente.

## Antes de publicar precisão sobre artigos

1. Fixar os PDFs permitidos e seus hashes; registrar origem, versão, licença e data, sem redistribuir documento sem permissão.
2. Duas pessoas revisarem independentemente os campos por experimento e a evidência (não apenas valor); resolver divergências. Registrar quem revisou e o que ainda é ambíguo. A ferramenta só recebe uma declaração de revisor, não comprova sua identidade.
3. Separar conjunto usado no desenvolvimento e conjunto de avaliação. Não ajustar prompt com base em todo o conjunto e chamar o mesmo resultado de teste independente.
4. Fixar modelo, parâmetros, prompt/hash, esquema e versão do extrator. Conteúdo do artigo é dado não confiável, nunca instrução executável. Não habilitar ferramentas, navegação ou execução de código em resposta ao texto do PDF.
5. Registrar saídas brutas e erros, inclusive documentos sem texto, campos ausentes, valores de tabelas não extraídos e associações incertas. Não descartar silenciosamente falhas.
6. Rodar o avaliador offline; publicar contagens e denominadores junto das proporções, custo real se houver e limites da amostra. Não usar nota dada por LLM como referência.

O conjunto real ainda está em revisão em `conjunto-referencia.md`. Não existe resultado de precisão científica nem chamada paga de IA nesta etapa. O benchmark operacional sintético, suas condições e limites ficam em `operacao.md`, separado deste protocolo.
