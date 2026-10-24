# Rascunho: termos de uso e aviso e retirada

> **Rascunho técnico, não é aconselhamento jurídico e não foi revisado por advogado.** Foi escrito por um assistente de programação a partir do que o sistema faz hoje. Antes de publicar, uma pessoa habilitada na **jurisdição escolhida para a hospedagem** precisa revisá-lo. As regras de responsabilidade de quem hospeda conteúdo de terceiros e de aviso e retirada variam entre países. Trechos entre `[colchetes]` são decisões do operador.

## 1. O que o sistema coleta (fatos, para quem for revisar)

| Dado | Onde | Observação |
|---|---|---|
| E-mail e hash Argon2id da senha | tabela `usuario` | senha nunca em claro |
| Token de sessão (só o SHA-256) e token CSRF | tabela `sessao` | cookie `__Host-atlas-sessao`, `HttpOnly`; é o único cookie |
| Endereço IP | tabela `limite_tentativa` (chave do contador) | em claro, apagado depois de no máximo 1 dia; também aparece nos logs do proxy |
| E-mail em contadores de login | `limite_tentativa` | só como SHA-256 |
| PDFs enviados, título declarado e hash | disco e `versao_documento` | o PDF pode conter dados pessoais de terceiros |
| Texto extraído, medidas, revisões e quem as fez (UUID da conta) | banco | |
| Consultas ao OpenAlex | servidor | DOIs e termos de busca saem do servidor para a OpenAlex; o IP do usuário não |

Backups contêm tudo isto, sem sessões e sem contadores; podem ser cifrados (`docs/backup.md`).
Não há análise de uso, anúncios, rastreadores de terceiros nem envio de e-mail.

## 2. Termos de uso (minuta)

1. **Quem somos.** O Atlas Científico é mantido por `[nome ou razão social do operador]`, contato em `[e-mail de contato]`.
2. **Conta.** Você cria uma conta com e-mail e senha e responde pelo que acontece nela. Podemos suspender contas usadas para abuso (por exemplo, enviar volume que prejudique o serviço).
3. **O que você pode enviar.** Só PDFs que você tem direito de armazenar e processar. Ao enviar, você declara ter essa autorização; **o sistema não a verifica**.
4. **Compartilhamento.** Seus documentos são **privados** até você marcá-los como públicos. Ao torná-lo público, você declara ter o direito de permitir que outras pessoas baixem aquele arquivo. Você pode voltar a privado a qualquer momento; cópias já baixadas por outras pessoas não podem ser recolhidas.
5. **Remoção.** `[Operador]` pode bloquear o compartilhamento de qualquer documento, a qualquer tempo, inclusive após uma notificação conforme a seção 3, e pode remover o arquivo.
6. **Dados extraídos não são conferidos.** Medidas, valores e trechos exibidos vêm de extração manual ou semiautomática. Podem conter erros. **Confira na fonte antes de citar ou usar em decisão.** O serviço é oferecido como está, sem garantia de exatidão, disponibilidade ou adequação a um fim específico, na medida em que a lei aplicável permitir.
7. **Limites.** Cada conta tem cota de armazenamento e de documentos, e há limite de tentativas de acesso. `[Valores vigentes]`.
8. **Mudanças e encerramento.** Os termos podem mudar; mudanças relevantes serão avisadas no site. `[Prazo e forma de exportação ou remoção de dados ao encerrar uma conta: o sistema ainda não tem exclusão de conta pela interface.]`
9. **Lei aplicável e foro.** `[A definir com assessoria jurídica conforme a jurisdição da hospedagem.]`

## 3. Aviso e retirada de conteúdo (minuta)

Se você é titular de direitos sobre um arquivo disponibilizado aqui e quer que ele seja retirado, escreva para `[e-mail de contato]` com:

1. seu nome, contato e, se for o caso, a quem representa;
2. a identificação da obra protegida;
3. onde o arquivo está no Atlas: o endereço `/api/publico/documentos/<id>/original` ou o identificador (UUID) do documento;
4. uma declaração de que você acredita de boa-fé que o uso não é autorizado pelo titular, por seus representantes ou pela lei;
5. uma declaração de que as informações do aviso são verdadeiras e de que você é o titular ou está autorizado a agir por ele;
6. sua assinatura (pode ser eletrônica).

**O que acontece:** o operador confere o aviso, bloqueia o compartilhamento do documento (sai da lista pública e do download) e avisa a conta responsável. `[Prazo-alvo: ex.: dentro de N dias úteis.]` Quem teve o documento bloqueado pode contestar respondendo ao e-mail com a justificativa; `[o operador decide se restabelece, conforme a lei aplicável]`. `[Política para contas reincidentes.]`

**Procedimento técnico do operador** (hoje manual; não há formulário nem painel):

```sql
-- bloqueia o compartilhamento (o dono não consegue desfazer pela API e continua vendo o próprio arquivo)
UPDATE versao_documento SET compartilhamento = 'BLOQUEADO' WHERE id = '<uuid>';
```

Para remover de vez: `./deploy/remover-documento.sh <uuid> "aviso de DD/MM/AAAA"`. Ele apaga o documento, as medidas e revisões ligadas a ele e o arquivo (se nenhuma outra conta tiver o mesmo PDF), e registra a remoção em `remocao_documento` sem guardar título nem conteúdo. Faça uma cópia antes: não há desfazer. Guarde o aviso recebido e a decisão fora do sistema.

## 4. Perguntas para a revisão jurídica

1. Em qual jurisdição o serviço ficará hospedado e qual regime de responsabilidade de intermediário (e de aviso e retirada) se aplica lá? Aplica-se também a lei dos países dos usuários?
2. O aviso acima atende aos requisitos formais dessa jurisdição? Quem deve ser o agente designado, se houver essa figura?
3. Um site aberto que **recebe** PDFs de terceiros e os **devolve** a outros (quando a conta os marca como públicos) muda o enquadramento em relação a só armazenar para o próprio dono?
4. O sistema trata dados pessoais (e-mail, IP, conteúdo dos PDFs). O autor decidiu não seguir a LGPD; a revisão deve dizer se alguma lei de proteção de dados ainda alcança o serviço por efeito (por exemplo, usuários no Brasil ou na União Europeia) e o que isso exige no mínimo.
5. É necessário aceite explícito dos termos no cadastro? Hoje o cadastro **não tem** caixa de aceite nem página de termos.
6. Retenção de logs e de IP, e prazo para guardar o registro dos avisos recebidos.
