# mechanical-hub-stock

Microsserviço de estoque do Mechanical Hub (Kotlin, Java 25, Spring Boot 4.1.1, PostgreSQL 16, SQS FIFO).

## Responsabilidade

Saldo de materiais por status (`AVAILABLE`, `RESERVED`), reserva, baixa e devolução dos materiais de uma ordem, pendências de estoque com fila por antiguidade, entrada de material e alerta de falta aos administradores. Preço de material é do `billing`; ordens e reparos são do `orders`; o estado da saga é do `orchestrator`.

## Papel na saga

Consome `mechanical-hub-stock-commands.fifo` (idempotente por `{transactionId}:{STEP}` no inbox) e publica em `mechanical-hub-orchestrator-events.fifo` via outbox.

| Comando | Resposta |
|---|---|
| `reserveStock` | `stockReserved` ou `stockPending` (reserva o disponível e grava o faltante) |
| `consumeStock` | `stockConsumed` |
| `restoreStock` | `stockRestored` e `stockReserved` de cada outra ordem pendente que zerar as faltas |

Uma entrada de material (`POST /stock/entry`) também resolve pendências por antiguidade e publica `stockReserved`. Invariante: se há pendência de um material, o `AVAILABLE` dele é 0.

## API (administrador, via API Gateway)

- `POST /stock/entry` com `Idempotency-Key` obrigatório: `{ "materialId": "...", "quantity": 10 }`
- `GET /stock`
- `GET /stock/{materialId}/movements`

Collection Postman: [`docs/api/mechanical-hub-stock.postman_collection.json`](docs/api/mechanical-hub-stock.postman_collection.json).

## Como rodar

```bash
docker compose up -d          # PostgreSQL e LocalStack (filas SQS)
./mvnw spring-boot:run        # Flyway aplica migrations e seeds
./mvnw -q verify              # build + testes unitarios, ktlint e detekt (nao precisa de Docker)
./mvnw -q verify -Pintegration-tests   # + testes de integracao (Testcontainers, exige Docker) e cobertura
```

Variáveis principais: `DB_NAME`, `DB_USER`, `DB_PASSWORD` (ver `compose.yaml` e `src/main/resources/application.yml`). Deploy: `Dockerfile`, `k8s/` e pipeline em `ci/`.

## Testes e cobertura

Unitários, integração (Testcontainers PostgreSQL), concorrência e fluxos da saga (`SagaFlowTests`: caminho feliz, pendência resolvida por entrada, recusa e pagamento não confirmado com redistribuição). Os testes de integração são marcados com `@Tag("integration")` e só rodam com o profile `integration-tests` (o CI sempre usa). O JaCoCo exige cobertura mínima de 80% nesse profile; o relatório fica em `target/site/jacoco/index.html` e é enviado ao SonarQube Cloud.
