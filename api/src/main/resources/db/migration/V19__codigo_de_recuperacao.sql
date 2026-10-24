-- Recuperação de senha sem e-mail (o projeto não tem servidor de e-mail): um código de 100 bits mostrado uma vez
-- ao criar a conta. Só o SHA-256 fica no banco; como o código tem alta entropia, um hash rápido basta.
ALTER TABLE usuario ADD COLUMN recuperacao_hash char(64);
