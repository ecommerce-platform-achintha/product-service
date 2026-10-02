# product-service

The marketplace catalog: admin-managed categories, merchant-owned products (items) with variants, stock with
reservations for orders, discounts, public search and product reviews. The contract is
[`docs/marketplace-design.md`](docs/marketplace-design.md) (sections 1, 3, 5, 9, 10 and 11 apply here).

- Java 25, Spring Boot 4.1.1, Spring Cloud 2025.1.3, Maven
- PostgreSQL, schema managed by **Flyway** (`src/main/resources/db/migration`), `ddl-auto=validate`
- Kafka: publishes `product-events` through a transactional outbox; consumes `user-events`, `store-events` and
  `order-events`
- JWTs validated against user-service's JWKS (RS256, issuer, audience, 30 s skew); deny-by-default security
- Config from the Config Server (`optional:configserver:http://localhost:8888`), registers with Eureka
- Port **8082**

## Prerequisites

| Dependency | Default location | Notes |
|---|---|---|
| Config Server | `http://localhost:8888` | `../config-server`, serves `config-repo/product-service.yml` |
| Eureka (service-registry) | `http://localhost:8761` | `../service-registry`; user-service is called through it (`lb://user-service`) |
| user-service | `http://localhost:8081` | JWKS (`/.well-known/jwks.json`), service tokens and security state |
| PostgreSQL | `localhost:5433`, db `productdb`, user/pass `productservice` | `docker compose up -d postgres` |
| Kafka | `localhost:9092` | the shared broker from `../order-service`. Optional: without it, events wait in the `outbox` table |

JDK 25, Maven 3.9+ and Docker (docker-compose, Testcontainers) are needed too.

The schema is a fresh Flyway baseline (`V1__init.sql`). Drop an old dev database once before the first start:
`docker compose down -v`.

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5433` / `productdb` | JDBC URL parts |
| `DB_USERNAME` / `DB_PASSWORD` | `productservice` / `productservice` | DB credentials (set `DB_PASSWORD` outside local) |
| `CONFIG_SERVER_URL` | `http://localhost:8888` | Config Server |
| `EUREKA_URL` | `http://localhost:8761/eureka/` | Eureka `defaultZone` |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka (outbox relay and consumers) |
| `KAFKA_TOPIC_USER_EVENTS` / `_STORE_EVENTS` / `_PRODUCT_EVENTS` / `_ORDER_EVENTS` | `user-events` / `store-events` / `product-events` / `order-events` | topic names |
| `JWKS_URI` | `http://localhost:8081/.well-known/jwks.json` | user-service public keys |
| `JWT_ISSUER` / `JWT_AUDIENCE` | `user-service` / `marketplace` | expected `iss` / `aud` |
| `JWT_CLOCK_SKEW_SECONDS` | `30` | `exp`/`nbf` tolerance |
| `SERVICE_CLIENT_SECRET_PRODUCT` | `local` profile: dev placeholder from the Config Server; otherwise **required** (secret) | client secret for user-service's `POST /internal/auth/service-token` (same value as in user-service) |
| `SHEDLOCK_ENABLED` | `true` | scheduler locks (one replica runs the outbox relay and the reservation safety net at a time) |
| `APP_TIMEZONE` | `Asia/Colombo` | display |

Other settings (Config Server or `application.yml`): `app.outbox.relay-interval` (PT1S) / `.batch-size` (100),
`app.scheduling.reservation-cleanup.interval` (PT5M) / `.grace` (PT1H) / `.batch-size` (200),
`app.search.engine` (`sql`), `app.catalog.low-stock-threshold` (5) / `.max-variants-per-product` (100),
`security.state-cache.max-size` (100000) / `.ttl` (PT10M), `security.internal.refresh-before-expiry` (PT30S),
`resilience4j.*.userService`, `app.ports.notification` / `app.ports.image-storage` (`mock`),
`app.ports.image-base-url`.

The service refuses to start outside the `local` profile when the service client secret is blank or still the
`LOCAL-DEV-ONLY` placeholder.

## Run locally

```bash
(cd ../config-server && mvn spring-boot:run)          # own terminal
(cd ../service-registry && mvn spring-boot:run)       # own terminal
(cd ../user-service && SPRING_PROFILES_ACTIVE=local mvn spring-boot:run)   # JWKS + internal endpoints
docker compose up -d postgres                         # PostgreSQL on 5433
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

Swagger UI: http://localhost:8082/swagger-ui.html (use **Authorize** with an access token from user-service).
Health: http://localhost:8082/actuator/health. Only `health` and `info` are exposed. `/internal/**` is not in the
API document.

## Docker

`docker compose up --build` runs the service and its PostgreSQL; Config Server, Eureka, user-service and Kafka are
reached on the host through `host.docker.internal` (override `CONFIG_SERVER_URL`, `EUREKA_URL`, `JWKS_URI`,
`KAFKA_BOOTSTRAP_SERVERS`). Outside the `local` profile, also set `SERVICE_CLIENT_SECRET_PRODUCT`. The
`Dockerfile` is multi-stage and runs as a non-root user.

## Security

- Every request with a bearer token is validated against user-service's JWKS: RS256 only, `iss`, `aud`,
  `exp`/`nbf` with 30 s skew, a subject and a known `role`. Identity always comes from the token (`sub`,
  `storeId`, `perms`, `status`). The old `GatewayUserHeaderFilter` (`X-User-Id`) is gone: headers are never
  trusted.
- **SecurityStateFilter** rejects revoked tokens with 401 `TOKEN_REVOKED`: a Caffeine cache (user id → token
  version and real status) is fed by `user-events` (`UserSecurityChanged`, `UserStatusChanged`); on a miss, or when
  a token is newer than the cache, it asks user-service `GET /internal/users/{id}/security-state` with a service
  token (**ServiceTokenProvider**, client credentials, cached until 30 s before expiry; Feign + Resilience4j retry,
  circuit breaker, 3 s time limit). If user-service cannot be reached the token is accepted (staleness bounded by
  the 10-minute token lifetime).
- Prefix rules (section 3.4), deny-by-default, repeated with `@PreAuthorize` on every controller method:

| Prefix | Who |
|---|---|
| `/api/public/**` (GET) | anyone |
| `/api/customer/**` | `ROLE_CUSTOMER` |
| `/api/merchant/**` | `ROLE_MERCHANT` (holds every permission), or `ROLE_ASSISTANT` with the route's permission: `PRODUCT_EDIT` (products), `STOCK_EDIT` (stock), `DISCOUNT_MANAGE` (discounts), `REVIEW_REPLY` (review replies). Product reads accept any of `PRODUCT_EDIT`, `STOCK_EDIT`, `DISCOUNT_MANAGE` |
| `/api/admin/**` | `ROLE_ADMIN`, `ROLE_SUPER_ADMIN` |
| `/internal/**` | `ROLE_SERVICE` (never routed by the gateway, not in the OpenAPI document) |
| anything else | denied (401 anonymous, 403 authenticated) |

- **Ownership (BOLA):** every merchant-side query is scoped by the JWT `storeId` (`findByPublicIdAndStoreId`,
  ...), never by request data; another store's ids answer **404**. Customer reviews are scoped by the JWT subject.
- A merchant whose token says `BANNED` can still read the catalog but not change it (403 `ACCOUNT_BANNED`); a
  `PENDING_APPROVAL` / `REJECTED` merchant can create drafts but not activate them (403 `MERCHANT_NOT_APPROVED`).
  A banned customer cannot review.
- Request and response DTOs only, never entities; server-controlled fields (`status` of new products, `storeId`,
  ratings, prices after discounts) are never taken from clients. Bean Validation on every DTO; free text (names,
  descriptions, option values, reviews, replies, reasons) is sanitized with the OWASP HTML sanitizer; LIKE
  wildcards are escaped; SQL is parameterised only.
- Every admin, merchant and assistant mutation (categories, products, status changes, stock edits, discounts,
  review replies and moderation) writes an append-only `audit_log` row (a trigger rejects updates and deletes).

## Catalog model

| Entity | Public id | Notes |
|---|---|---|
| Category | `slug` | admin CRUD; flat with an optional parent (two levels at most); unique slug and name; only unused categories can be deleted |
| Product (item) | `ITM-YYMM-XXXXXX` | `storeId` from the JWT; `DRAFT` / `ACTIVE` / `ARCHIVED`; `codAllowed`; rating aggregates; `imageKeys` |
| Product option | (by name) | e.g. Size: [S, M, L]; up to 5 per product |
| Variant | `VAR-YYMM-XXXXXX` | merchant SKU **unique per store** (case-insensitive, unique index), attributes (one value per option), `listPrice`, `ACTIVE` / `INACTIVE`, `imageKeys` |
| Inventory | (per variant) | `quantityAvailable` (sellable), `reservedQuantity` (held), `@Version`, CHECK constraints: never negative |
| Stock reservation | (per `orderRef` + variant) | `HELD` → `COMMITTED` or `RELEASED` (reason `RELEASED` / `ADJUSTED` / `EXPIRED`), `expiresAt` |
| Discount | `DSC-YYMM-XXXXXX` | `PRODUCT` or `VARIANT` scope, `PERCENT` (1-90) or `FIXED` (must leave a price > 0), `startsAt` / `endsAt`, `active` |
| Product review | `REV-YYMM-XXXXXX` | one per order item, rating 1-5, sanitized text, `attachmentKeys` (mock storage); one store reply; admin hide/unhide |

- **The product is one aggregate.** `POST /api/merchant/products` creates it with its options and variants
  (status `DRAFT`). A simple product sends `sku` + `listPrice` (+ `initialStock`) and gets one default variant.
  `PUT` replaces the aggregate: listed variants are updated (by `publicId`) or added (with `initialStock`), the
  others become `INACTIVE` (variants are never deleted: holds, orders and reviews refer to them, and they keep
  their SKU). Stock is not changed by `PUT`.
- **Status:** `DRAFT ↔ ACTIVE`, `DRAFT/ACTIVE → ARCHIVED` (`DELETE` archives), `ARCHIVED → DRAFT`. Activation
  needs an active variant and an approved merchant. Archived products cannot be edited.
- **Public visibility:** a product is listed and shown only when it is `ACTIVE`, at least one variant is active,
  and its store is visible: the `store_visibility` cache says `visible` (store-service's
  `published && acceptingOrders`, from `StoreVisibilityChanged`) and the owner's status (from `UserStatusChanged`)
  is `ACTIVE` or not reported. A merchant in `BAN_GRACE` or `BANNED` leaves search at once; the merchant's own
  views do not show visibility, so the silent ban stays silent (D4). A pending merchant's store is never announced,
  so its drafts are never public.

## Prices and discounts

Effective price = list price minus the **single best** discount that applies **now** (evaluated at read time, no
stacking): the discount is `active`, `startsAt <= now < endsAt` (open ends allowed), targets the variant or its
product, and leaves a price above zero. Percent amounts are rounded half-up to cents. Ties go to the
variant-specific discount, then the oldest. The same rule is used by the public views, the internal quote and the
search price filter/sort (`PriceCalculator` in Java, the same arithmetic in `SqlProductSearchEngine`).

## Stock and reservations

- **Merchant stock edit** (`STOCK_EDIT`): `PATCH /api/merchant/variants/{publicId}/stock` with
  `{"mode":"SET"|"DELTA","quantity":n,"reason":"...","expectedVersion":v?}` changes the available quantity (held
  units untouched), never below zero (409 `INSUFFICIENT_STOCK`), with `@Version` optimistic locking (409
  `CONCURRENT_MODIFICATION` on a stale `expectedVersion` or a concurrent change) and an audit row with the reason.
  The old user-callable `PATCH /api/products/{id}/inventory` is removed.
- **Reservations** (order-service only, `ROLE_SERVICE`), all idempotent per `orderRef` and serialised per order by a
  transaction-scoped PostgreSQL advisory lock:
  - `POST /internal/reservations`: all lines or none. Each line is one conditional update
    (`... where quantity_available >= :qty`), so concurrent orders never oversell; variants are locked in a fixed
    order (no deadlocks). Lines must be active variants of `ACTIVE` products of one visible store (409
    `VARIANT_NOT_AVAILABLE`). A replay with the same lines answers 200 with the existing hold; different lines answer
    409 `RESERVATION_CONFLICT`.
  - `PUT /internal/reservations/{orderRef}` (quote): the lines to keep; quantities may only go down (400
    `RESERVATION_ADJUST_NOT_ALLOWED` otherwise); left-out or 0 lines are released (`ADJUSTED`); optional new
    `expiresAt`.
  - `POST .../commit`: held units leave the stock for good (idempotent). `POST .../release`: held units return to
    available (idempotent; 409 `RESERVATION_COMMITTED` after a commit).
  - Safety net: `ReservationCleanupJob` (every 5 min, ShedLock) releases holds still `HELD` one hour after their
    `expiresAt` (reason `EXPIRED`) and alerts admins through `NotificationPort`. order-service stays authoritative.

## Search

`GET /api/public/products` goes through the `ProductSearchEngine` interface; the current implementation
(`SqlProductSearchEngine`, `app.search.engine=sql`) is plain PostgreSQL: a case-insensitive match on the name
(wildcards escaped; trigram GIN index on `lower(name)`), relevance = prefix match, then trigram similarity, then
newest. A real search engine can replace it (e.g. fed by `product-events`) without touching the API.

| Param | Meaning |
|---|---|
| `q` | name contains (case-insensitive) |
| `category` | category slug, its sub-categories included (unknown: empty result) |
| `store` | store public id `STR-...` (unknown: empty result) |
| `minPrice` / `maxPrice` | on the effective "from" price (cheapest active variant after its discount); 400 if min > max |
| `inStock` | at least one active variant has stock available |
| `minRating` | average rating at least |
| `sort` | `relevance` (default), `price` / `price,asc`, `price,desc`, `newest`, `rating` (anything else: 400 `INVALID_SORT`) |
| `page` / `size` | max size 50 |

Public responses carry public ids only, no SKUs and no stock counts: `inStock` / `lowStock` flags only.

## Reviews

- `OrderCompleted` (order-events) records `review_eligibility`, one row per (order, variant), idempotently.
- `POST /api/customer/products/{publicId}/reviews` with `{orderPublicId, variantPublicId?, rating, text,
  attachmentKeys}`: only for an item of the caller's completed order (404 `REVIEW_NOT_ELIGIBLE`), once per order
  item (409 `REVIEW_ALREADY_EXISTS`). The product's `ratingAvg` / `ratingCount` count visible reviews.
- One store reply per review (`REVIEW_REPLY`, 409 `REPLY_ALREADY_EXISTS`); admins hide / unhide with a reason (the
  rating is recomputed).

## Endpoints

| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/api/public/categories` | anyone | active categories |
| GET | `/api/public/products` | anyone | search (above) |
| GET | `/api/public/products/{publicId}` | anyone | visible product, active variants, effective prices, discount, availability |
| GET | `/api/public/products/{publicId}/reviews` | anyone | visible reviews with replies (sort `createdAt`, `rating`) |
| GET | `/api/merchant/products` | merchant; assistant `PRODUCT_EDIT`/`STOCK_EDIT`/`DISCOUNT_MANAGE` | own store, `status`, `q`, sort `createdAt`/`updatedAt`/`name` |
| GET | `/api/merchant/products/{publicId}` | same | aggregate with stock and current prices |
| POST | `/api/merchant/products` | merchant; assistant `PRODUCT_EDIT` | create (DRAFT) |
| PUT | `/api/merchant/products/{publicId}` | same | replace the aggregate |
| POST | `/api/merchant/products/{publicId}/status` | same | `{"status":"DRAFT"\|"ACTIVE"\|"ARCHIVED"}` |
| DELETE | `/api/merchant/products/{publicId}` | same | archive |
| PATCH | `/api/merchant/variants/{publicId}/stock` | merchant; assistant `STOCK_EDIT` | SET / DELTA with reason |
| GET, POST | `/api/merchant/discounts` | merchant; assistant `DISCOUNT_MANAGE` | list (`product=ITM-...`), create |
| GET, PUT, DELETE | `/api/merchant/discounts/{publicId}` | same | |
| GET | `/api/merchant/products/reviews` | merchant; assistant `REVIEW_REPLY` | the store's reviews (`product=ITM-...`) |
| POST | `/api/merchant/products/reviews/{reviewPublicId}/reply` | same | one reply |
| POST | `/api/customer/products/{publicId}/reviews` | customer | review a completed order item |
| GET, POST | `/api/admin/categories` | admin, super admin | list all, create |
| GET, PUT, DELETE | `/api/admin/categories/{slug}` | same | |
| GET | `/api/admin/products?store=STR-...` | same | a store's products for moderation (max size 100) |
| GET | `/api/admin/products/{publicId}` | same | any product |
| GET | `/api/admin/reviews?product=ITM-...` | same | all reviews of a product |
| POST | `/api/admin/reviews/{reviewPublicId}/hide`, `/unhide` | same | `{"reason":"..."}` |
| POST | `/internal/variants/quote` | `ROLE_SERVICE` | `{variantIds[], variantPublicIds[]}` → per variant: storeId/publicId/name, product and variant names, SKU, attributes, `listPrice`, `discountAmount`, `unitPrice`, `discountPublicId`, `availableQuantity`, `codAllowed`, `productActive`, `variantActive`, `storeVisible`, `purchasable`; plus `missing[]` |
| POST | `/internal/reservations` | `ROLE_SERVICE` | `{orderRef, expiresAt, lines:[{variantId\|variantPublicId, quantity}]}`; 201 new, 200 replay |
| GET, PUT | `/internal/reservations/{orderRef}` | `ROLE_SERVICE` | read; adjust (reduce only) |
| POST | `/internal/reservations/{orderRef}/commit`, `/release` | `ROLE_SERVICE` | |

Errors keep the JSON shape `{timestamp, status, error, code, message, path, fieldErrors}`; `code` is stable
(`ErrorCode`), e.g. `VALIDATION_FAILED`, `NOT_FOUND`, `TOKEN_REVOKED`, `SKU_TAKEN`, `INVALID_VARIANTS`,
`INVALID_STATUS_TRANSITION`, `PRODUCT_NOT_SELLABLE`, `INSUFFICIENT_STOCK`, `VARIANT_NOT_AVAILABLE`,
`RESERVATION_CONFLICT`, `RESERVATION_ADJUST_NOT_ALLOWED`, `RESERVATION_COMMITTED`, `RESERVATION_RELEASED`,
`INVALID_DISCOUNT`, `REVIEW_NOT_ELIGIBLE`, `REVIEW_ALREADY_EXISTS`, `REPLY_ALREADY_EXISTS`, `CATEGORY_IN_USE`.

## Events

- **Published** (outbox written in the same transaction, relayed by `OutboxRelay` every second, at-least-once,
  keyed by the product UUID): topic `product-events`, `ProductChanged` with `eventId`, `eventType`, `occurredAt`,
  `productId`, `productPublicId`, `storeId`, `name`, `status`, `previousStatus`, `categorySlug`, `codAllowed`,
  `variants[{variantId, variantPublicId, sku, listPrice, status}]`, `change` (`CREATED`, `UPDATED`,
  `STATUS_CHANGED`). No stock counts.
- **Consumed** (group `product-service`; idempotent through `processed_events`; failures retried with back-off,
  unreadable messages skipped):
  - `user-events`: `UserSecurityChanged` / `UserStatusChanged` → security-state cache; a merchant's
    `UserStatusChanged` → owner status in `store_visibility`.
  - `store-events`: `StoreVisibilityChanged` → `store_visibility` (store public id, name, visible, COD); a late
    event never overwrites a newer one.
  - `order-events`: `OrderCompleted` (`orderId`, `orderPublicId`, `customerId`, `lines[{variantId|variantPublicId}]`)
    → review eligibility.

## Mock ports

`ImageStoragePort` (`MockImageStorageAdapter`: every key exists, URL = `app.ports.image-base-url` + key) and
`NotificationPort` (`MockNotificationAdapter`: logs store and admin notifications such as a new review, low stock or
safety-net releases), selected with `@ConditionalOnProperty` so real providers can be plugged in later.

## Tests

```bash
mvn clean verify
```

Integration tests run the full application against PostgreSQL (Testcontainers), an embedded Kafka broker and a
WireMock user-service (JWKS); Docker is required.

- `AuthorizationMatrixTest`: every endpoint × anonymous, customer, merchant, assistant with and without the
  permission, admin, super admin and service (401/403/404/2xx), including deny-by-default and the removed paths.
- `OwnershipTest`: merchant A vs B (404 everywhere), assistants and route permissions, customers/admins on merchant
  routes, revoked tokens, banned and pending merchants, and that no handler is mapped to the old
  `/api/products/{id}/inventory`.
- `InventoryTest`: idempotent / all-or-nothing reserve, adjust only reduces, commit / release idempotency and
  exclusivity, the safety net, merchant stock edits with audit and optimistic locking.
- `ReservationConcurrencyTest`: 40 parallel buyers for 10 units → exactly 10 holds; parallel replays of one order
  hold once; parallel commit/release of one order apply only one.
- `SearchVisibilityTest`: draft, archived, hidden store, `BAN_GRACE` and banned owners, pending merchants, products
  without active variants are not listed; effective-price filter and sort, stock, rating, store, category with
  sub-categories, relevance and escaped text search, availability flags, no SKUs or counts in public views.
- `PriceCalculatorTest` / `DiscountApiTest`: windows, percent/fixed, best wins, no stacking, validation, prices in
  public views and the internal quote over time.
- `ProductServiceTest`: default variants, option/variant validation, SKU uniqueness per store, update semantics,
  status lifecycle, `ProductChanged` outbox rows and audit rows, merchant list paging.
- `EventConsumerTest` / `ProductFlowIntegrationTest`: consumer idempotency and ordering; the whole life of a product
  across Kafka (store visibility, quote, reserve, commit, `OrderCompleted`, review, reply, moderation, silent ban,
  `ProductChanged` relayed to `product-events`).
- `ProductSpecificationsTest`, `OpenApiDocsTest`: escaping, sort parsing, public ids, sanitising; API docs list the
  public routes and no internal ones.
