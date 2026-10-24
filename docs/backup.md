# Cópias de segurança e restauração

Scripts em `deploy/`: `backup.sh`, `restaurar.sh` e `backup-teste.sh`. Servem ao Docker Compose privado de `deploy/compose.yml` (projeto padrão `atlas`).

## Fazer uma cópia

```bash
ATLAS_BACKUP_DIR=/caminho/fora/do/servidor-de-aplicacao ./deploy/backup.sh
```

Cada cópia é uma pasta com carimbo UTC: `banco.dump` (`pg_dump -Fc`), `documentos.tar` e `SHA256SUMS`. Ela é montada em `*.parcial` e só ganha o nome final quando termina; uma falha não deixa cópia que pareça válida. `ATLAS_BACKUP_MANTER` (padrão 7) apaga as mais antigas, e só dentro de `ATLAS_BACKUP_DIR`. Use `cron` ou um timer do `systemd` para repetir; **agendar não está configurado**.

Decisões e o porquê:

- **Banco antes dos arquivos.** Os PDFs são gravados antes da linha no banco e nunca mudam (o nome é o hash). Copiar os arquivos depois do dump garante que todo documento citado no dump está na cópia; o contrário poderia deixar o banco apontando para um arquivo ausente.
- **Sem dados de `sessao` e `limite_tentativa`.** Um backup não deve ressuscitar sessões abertas nem contadores. A estrutura das tabelas é copiada.
- **Permissões `umask 077`.** A cópia contém e-mails, hashes de senha e os PDFs de todos. **Os scripts não criptografam**: guarde em disco/bucket cifrado ou passe a saída por `age`/`gpg` antes de mandar para fora da máquina.

## Restaurar

```bash
ATLAS_RESTAURAR_DOCUMENTOS=/pasta/vazia ./deploy/restaurar.sh /caminho/backups/20261024T120000Z
```

Confere o `SHA256SUMS`, cria um banco **novo** (`atlas_restaurado`, ou `ATLAS_RESTAURAR_BANCO`) e extrai os documentos numa pasta **vazia**. Recusa: cópia adulterada ou incompleta, restaurar sobre o banco `atlas`, banco que já existe e pasta com conteúdo. Depois de conferir, aponte `ATLAS_DB_URL` e `ATLAS_DOCUMENTOS_DIR` para o restaurado. O histórico do Flyway vai junto no dump, então a aplicação valida o esquema ao subir.

## O que está provado

`deploy/backup-teste.sh` roda no CI e localmente sem subir a API: usa um projeto compose descartável só com o PostgreSQL, aplica as migrações, cria dados e um arquivo, faz três cópias com retenção 2, restaura numa base nova e compara contagens, esquema (tabelas, índices, restrições e gatilhos), o conteúdo do arquivo por SHA-256 e a ausência das sessões. Também verifica as recusas. Foi conferido por mutação: retirar a exclusão de `sessao` do `pg_dump` faz o teste falhar.

## O que NÃO está provado

- Restauração com a **API de pé** sobre o banco restaurado, e o volume real do compose em produção.
- Tempo e tamanho com dados reais; nada foi medido além de um documento de teste.
- Cópia para fora da máquina, criptografia e agendamento: não existem.
- Perda total do servidor: uma cópia no mesmo disco não protege contra isso; o destino deve ser outro lugar.
- Restauração parcial (um usuário ou documento): não há; o `pg_restore` seria por tabela e manual.
