# product-service

Spring Boot microservice for the product catalog: categories, products, and product stock (inventory).

- Java 25, Spring Boot 4.1.1, Spring Cloud 2025.1.3, Maven
- PostgreSQL via Spring Data JPA (`ddl-auto=update` for now; Flyway/Liquibase to follow)
- Config from the Config Server (`optional:configserver:http://localhost:8888`)
- Registers with Eureka (`http://localhost:8761/eureka/`)
- Port **8082**

## Prerequisites

Start these **before** product-service:

| Dependency | Default location | Notes |
|---|---|---|
| Config Server | `http://localhost:8888` | `../config-server`. Optional: the service starts without it |
| Eureka (service-registry) | `http://localhost:8761` | `../service-registry` |
| PostgreSQL | `localhost:5433`, db `productdb`, user/pass `productservice`/`productservice` | `docker compose up -d postgres` starts one with these defaults |

The database is on port **5433** with its own name and volume, so it can run next to user-service's
PostgreSQL on 5432.

You also need JDK 25, Maven 3.9+, and Docker (for docker-compose and the Testcontainers integration test).

## Configuration

Every setting can be overridden with an environment variable:

| Env var | Default | Purpose |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5433` / `productdb` | JDBC URL parts |
| `DB_USERNAME` / `DB_PASSWORD` | `productservice` / `productservice` | DB credentials |
| `CONFIG_SERVER_URL` | `http://localhost:8888` | Config Server |
| `EUREKA_URL` | `http://localhost:8761/eureka/` | Eureka `defaultZone` |

## Run locally

```bash
# 1. Config Server and Eureka (in their own terminals)
(cd ../config-server   && mvn spring-boot:run)
(cd ../service-registry && mvn spring-boot:run)

# 2. PostgreSQL only
docker compose up -d postgres

# 3. product-service
mvn spring-boot:run
```

Swagger UI: http://localhost:8082/swagger-ui.html
Health check: http://localhost:8082/actuator/health

## Run with docker-compose

This starts product-service and its PostgreSQL with one command. Config Server and Eureka must
still be running on the host. The container reaches them through `host.docker.internal`.

```bash
docker compose up --build
```

To point the container elsewhere, override `CONFIG_SERVER_URL` / `EUREKA_URL`
(e.g. `CONFIG_SERVER_URL=http://config-server:8888 docker compose up`).
Stop with `docker compose down` (add `-v` to also delete the database volume).

## API

No authentication yet. Access control will be enforced by the API Gateway.

| Method | Path | Description |
|---|---|---|
| POST | `/api/categories` | Create a category (201). `name` must be unique (409 otherwise) |
| GET | `/api/categories` | List all categories, sorted by name |
| POST | `/api/products` | Create a product (201) with its inventory row set to 0. `sku` must be unique (409), and the category must exist (404) |
| GET | `/api/products/{id}` | One product, including its category and current stock |
| PUT | `/api/products/{id}` | Replace the product fields (same body as POST) |
| DELETE | `/api/products/{id}` | Delete the product and its inventory (204) |
| GET | `/api/products` | Paginated search, described below |
| PATCH | `/api/products/{id}/inventory` | Body `{"delta": int}`. Adds `delta` to `quantityAvailable`, or returns **409** if the result would be negative |

### Search parameters (`GET /api/products`)

All parameters are optional, and the filters combine with AND.

| Param | Example | Meaning |
|---|---|---|
| `categoryId` | `categoryId=4a21…` | Only products in this category |
| `minPrice` / `maxPrice` | `minPrice=10&maxPrice=50` | Inclusive price range (400 if `minPrice > maxPrice`) |
| `q` | `q=mouse` | Case-insensitive partial match on the name |
| `sort` | `sort=price,asc` | One of `name`, `price`, `sku`, `createdAt`, `updatedAt`. Repeatable. Default `name,asc` |
| `page` / `size` | `page=0&size=20` | Zero-based page. `size` defaults to 20 and is capped at 100 |

Response shape:

```json
{ "content": [ { "id": "…", "name": "…", "price": 24.99, "sku": "…",
                 "category": { "id": "…", "name": "…" },
                 "stock": { "quantityAvailable": 25, "reservedQuantity": 0 }, "…": "…" } ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1 }
```

Errors always use the same JSON shape (400 validation, 404 not found, 409 conflict / insufficient stock):

```json
{
  "timestamp": "2026-09-29T05:26:32.670Z",
  "status": 409,
  "error": "Conflict",
  "message": "Insufficient stock for product d1b4…: available 25, requested change -30",
  "path": "/api/products/d1b4…/inventory"
}
```

Validation errors also include `"fieldErrors": [{ "field": "price", "message": "…" }]`.

### Example curl session

```bash
# Create a category -> 201
CATEGORY_ID=$(curl -s -X POST http://localhost:8082/api/categories \
  -H 'Content-Type: application/json' \
  -d '{"name":"Electronics","description":"Gadgets and devices"}' | jq -r .id)

# Create a product -> 201, stock starts at 0
PRODUCT_ID=$(curl -s -X POST http://localhost:8082/api/products \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"Wireless Mouse\",\"description\":\"2.4GHz, USB-C\",\"price\":24.99,
       \"sku\":\"MOUSE-001\",\"categoryId\":\"$CATEGORY_ID\"}" | jq -r .id)

# Get one product (with category and stock)
curl -s http://localhost:8082/api/products/$PRODUCT_ID

# List with filters: category + price range + name search, cheapest first
curl -s "http://localhost:8082/api/products?categoryId=$CATEGORY_ID&minPrice=10&maxPrice=50&q=mouse&sort=price,asc&page=0&size=20"

# Adjust inventory: restock 25 -> {"productId":"…","quantityAvailable":25,"reservedQuantity":0}
curl -s -X PATCH http://localhost:8082/api/products/$PRODUCT_ID/inventory \
  -H 'Content-Type: application/json' -d '{"delta":25}'

# Take 30 out -> 409 Conflict, quantity stays 25
curl -i -X PATCH http://localhost:8082/api/products/$PRODUCT_ID/inventory \
  -H 'Content-Type: application/json' -d '{"delta":-30}'
```

## Design notes

- **Inventory is its own table** (`inventory`, one-to-one with `products`). Stock changes much more often
  than product details, so keeping it separate isolates that write traffic and its row locks from catalog reads
  and edits.
- **Concurrent stock updates are safe.** `PATCH …/inventory` locks the inventory row
  (`SELECT … FOR UPDATE`) before checking and applying the delta. Two orders racing for the last item are
  therefore handled one after the other: one succeeds and the other gets a 409.
- **Search** is built with Spring Data JPA Specifications (`ProductSpecifications`). Each filter that is present
  adds one predicate, so every combination works without a query written for it. LIKE wildcards in `q` are
  escaped. Each page loads its categories in the same query (entity graph) and its stock with one extra query.
- **Indexes:** `idx_products_category_id` (category filter) and a unique `idx_products_sku` (SKU lookups and
  uniqueness), declared with `@Table(indexes = …)`.

## Tests

```bash
mvn clean package
```

- `ProductSpecificationsTest`: which predicates each filter combination builds (mocked Criteria API),
  that blank or absent filters add no restriction, and LIKE escaping
- `ProductServiceTest`: rejects `minPrice > maxPrice` and unknown sort fields, and adds a stable sort tie-breaker
- `InventoryTest`: the negative-quantity guard (a rejected change leaves stock untouched), overflow, and the
  service's 404 path
- `ProductFlowIntegrationTest`: runs against a real PostgreSQL through Testcontainers (Docker required).
  It covers create product → list with a category filter (plus combined filters, sorting and paging) →
  adjust inventory → verify quantity, a 409 on negative stock, update and delete, and the 400/404/409 error bodies.
