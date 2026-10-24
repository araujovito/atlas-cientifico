# Conjunto de referência: candidatos iniciais

Estado: seleção preliminar, ainda não é um conjunto de avaliação. Texto XML consultado em 2026-10-24; nenhum PDF foi incluído no repositório.

## Candidato A — texto de artigo sobre cátodo NCM

- Negi RS, Elm MT. “Reproducible long-term cycling data of Al₂O₃ coated LiNi₀.₇₀Co₀.₁₅Mn₀.₁₅O₂ cathodes for lithium-ion batteries.” *Scientific Data* (2022). DOI: [10.1038/s41597-022-01217-5](https://doi.org/10.1038/s41597-022-01217-5). PMCID: [PMC8967842](https://pmc.ncbi.nlm.nih.gov/articles/PMC8967842/). Texto completo XML: [Europe PMC](https://www.ebi.ac.uk/europepmc/webservices/rest/PMC8967842/fullTextXML).
- **Por que é candidato:** o artigo descreve dados de capacidade específica de descarga e impedância eletroquímica de células com cátodos NCM, relevantes ao recorte do Atlas. A Europe PMC disponibiliza o XML de texto completo e identifica o artigo como acesso aberto.
- **Inspeção estrutural do XML:** há três tabelas e quatro figuras; a Tabela 2 é descrita como metadados de número de ciclo, capacidade de descarga, resposta de impedância e retenção de capacidade. Isso torna o texto promissor para o recorte, mas não confirma que o PDF exponha as mesmas relações de forma legível nem fornece páginas para as evidências.
- **Licença do artigo:** o XML do texto completo declara Creative Commons Attribution 4.0 International (CC BY 4.0). A licença permite compartilhar e adaptar o artigo com atribuição, link para a licença e indicação de alterações; materiais de terceiros podem ter créditos/licenças próprios e precisam ser conferidos individualmente.
- **Dados associados:** o artigo aponta para o projeto OSF [osf.io/ybd6f](https://osf.io/ybd6f/). A API pública do OSF o identifica como público, mas retorna `license: null`. Portanto, não copiar, baixar para redistribuição nem tratar os arquivos depositados no OSF como licenciados até confirmar os termos diretamente com os autores ou com o repositório.
- **Uso permitido nesta etapa:** avaliar o texto do artigo sob CC BY para extração e rastreabilidade, mantendo metadados, atribuição e proveniência. Não incluir o PDF no Git. Conferir os créditos de figuras e demais materiais antes de reutilizá-los.
- **Inspeção do PDF da editora:** cinco páginas. A página 2 explica a fórmula de retenção; a página 3 contém figuras, e a legenda da Figura 2 diz que dados de três células foram reproduzidos de outro trabalho com permissão; a página 4 contém uma curva de retenção e a Tabela 2, que descreve metadados, não observações numéricas.
- **Decisão provisória:** manter apenas como candidato para testar leitura do texto, não para gabarito. Não copiar valores das figuras reproduzidas. Como os dados numéricos estão no dataset do OSF e a licença dele continua nula, não usar esse conjunto até esclarecer os termos. Ainda falta segunda revisão humana.

## Outros candidatos para ampliar os formatos

Estes artigos também declaram CC BY 4.0 no XML de texto completo servido pela Europe PMC. A inspeção estrutural inicial sugere que suas tabelas não são as melhores fontes para métricas de desempenho; ficam como candidatos secundários, ainda sem PDF ou anotação.

- Sun X, et al. “Effect of Different Composition on Voltage Attenuation of Li-Rich Cathode Material for Lithium-Ion Batteries.” *Materials* (2019). DOI: [10.3390/ma13010040](https://doi.org/10.3390/ma13010040). PMCID: [PMC6981382](https://pmc.ncbi.nlm.nih.gov/articles/PMC6981382/). [Texto completo XML](https://www.ebi.ac.uk/europepmc/webservices/rest/PMC6981382/fullTextXML).
  - **Observação de escopo:** o XML tem uma tabela sobre razões de intensidade de picos de difração de raios X; resultados eletroquímicos aparecem em prosa e figuras. Pode ajudar a testar campos que não devem ser confundidos com capacidade, mas não é o primeiro candidato para uma linha de comparação de desempenho.
- “Construction of Uniform LiF Coating Layers for Stable High-Voltage LiCoO₂ Cathodes in Lithium-Ion Batteries.” *Molecules* (2024). DOI: [10.3390/molecules29061414](https://doi.org/10.3390/molecules29061414). PMCID: [PMC10974255](https://pmc.ncbi.nlm.nih.gov/articles/PMC10974255/). [Texto completo XML](https://www.ebi.ac.uk/europepmc/webservices/rest/PMC10974255/fullTextXML).
  - **Observação de escopo:** a tabela principal contém parâmetros de rede; parte dos resultados eletroquímicos está em figuras e informação suplementar. Não usar arquivos suplementares até confirmar seus termos/licenças.

## Anotação candidata — capacidade específica reportada em prosa

- Zhang Y, Wu Y, Li H, Chen J, Lei D, Wang C. “A dual-function liquid electrolyte additive for high-energy non-aqueous lithium metal batteries.” *Nature Communications* (2022). DOI: [10.1038/s41467-022-28959-5](https://doi.org/10.1038/s41467-022-28959-5). PMCID: [PMC8917126](https://pmc.ncbi.nlm.nih.gov/articles/PMC8917126/). [PDF da editora](https://www.nature.com/articles/s41467-022-28959-5.pdf), [XML da Europe PMC](https://www.ebi.ac.uk/europepmc/webservices/rest/PMC8917126/fullTextXML).
- O XML do texto completo declara CC BY 4.0. O PDF da versão publicada foi baixado para inspeção local em 2026-10-24 e removido ao final; SHA-256 verificado: `6d27a98aa4a76a07765378fb492b5f7949e1daad621a85a8ae26f98f6b3bc313`. O PDF não foi adicionado ao Git.
- **Anotação ainda pendente de segunda revisão:** objeto `Li || NCM622 coin cell`; métrica `specific discharge capacity`; valor original `98.2`; unidade original `mAh g−1`; estado `REPORTADO`; evidência na página física 6: “98.2 mAh g−1”. A frase circundante atribui o resultado ao eletrólito AFE e diz que as células chegaram a 1000 ciclos.
- **Condições associadas reportadas no mesmo parágrafo:** carga de cátodo de 2.4 mg cm−2; corrente de 19, 38, 95, 190, 380 e 950 mA g−1 por cinco ciclos em cada taxa, seguida por estabilização em 190 mA g−1. O artigo define os aditivos da formulação como 0.4 wt% de Al(EtO)₃ e 5 vol% de FEC em eletrólito carbonato com LiPF₆. Preservar essas condições no experimento; não comparar esse resultado isoladamente como se fosse propriedade intrínseca do cátodo.
- É um exemplo útil de valor em prosa com condições importantes, mas não uma anotação gold: a evidência é curta e depende do contexto da frase e da configuração do teste. Uma pessoa revisora precisa confirmar a associação de cada condição antes de usar o item para avaliar extração.

As licenças de figuras e materiais de terceiros devem ser verificadas nos próprios artigos. A presença dos candidatos nesta lista não significa que os PDFs, figuras ou dados suplementares já foram baixados, incorporados ou revisados.

## Critérios para aceitar itens no conjunto

1. A licença do texto integral deve permitir o processamento pretendido; acesso aberto sozinho não basta.
2. Registrar DOI/PMCID, versão, URL de origem, licença, data de verificação e SHA-256 do arquivo usado localmente.
3. Conferir licenças de figuras, tabelas e dados suplementares separadamente quando não estiverem claramente cobertos.
4. Anotar manualmente valor original, unidade, condições, página e trecho; marcar como ausente quando o artigo não reportar o campo.
5. Ter uma segunda revisão humana antes de usar qualquer anotação para calcular métricas.

Este documento registra candidatos e uma anotação pendente de revisão, não uma afirmação de que o conjunto de referência já foi validado ou medido.

## Seleção para demonstração em 2026-10-24

Dois artigos foram escolhidos para demonstrar a busca por DOI. Os XMLs da Europe PMC foram consultados novamente nesta rodada e ambos declaram CC BY 4.0 para o artigo, com ressalvas para material de terceiros:

| DOI | Uso nesta demonstração | Consulta pela API do Atlas contra OpenAlex |
|---|---|---|
| `10.1038/s41467-022-28959-5` | Metadados; a anotação em prosa descrita acima continua candidata para o fluxo manual de evidência | HTTP 200, `W4220682882` |
| `10.1038/s41597-022-01217-5` | Metadados e, em demonstração futura, leitura de texto; evitar figuras reproduzidas e o dataset OSF com licença não confirmada | HTTP 200, `W4220906497` |

Esta rodada não baixou nem incorporou PDFs e não voltou a verificar seus hashes. Os testes automatizados de upload continuam usando PDFs sintéticos. A licença do XML do artigo foi reconferida; créditos específicos de figuras/datasets e a revisão humana das anotações continuam pendentes. Esses dois itens não formam um gabarito de avaliação.
