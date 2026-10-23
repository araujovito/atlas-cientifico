# Ambiente de desenvolvimento

Testado em Linux, em 2026-10-22.

## Requisitos

- JDK 21 (Eclipse Temurin 21.0.13+9)
- Docker (os testes usam Testcontainers com Postgres)

O Maven vem pelo wrapper (`api/mvnw`); não precisa instalar.

## JDK sem permissão de administrador

O JDK foi instalado só para o usuário, em `~/.local/jdk`, a partir do tarball da Adoptium. O SHA-256 foi conferido contra os metadados da própria Adoptium antes de extrair.

```bash
export JAVA_HOME=$(ls -d ~/.local/jdk/jdk-21*)
export PATH=$JAVA_HOME/bin:$PATH
java -version
```

## Rodar os testes

```bash
cd api
./mvnw test
```

Na primeira execução o Testcontainers baixa a imagem do Postgres.
