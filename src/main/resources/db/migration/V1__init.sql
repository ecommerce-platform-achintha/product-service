-- product-service baseline schema (docs/marketplace-design.md, sections 1, 5, 9 and 11).
-- Maintained from now on: never edit this file once applied, add V2__... instead.
-- UUID primary keys stay internal; every customer-facing entity also has a unique human-readable public_id.

-- Trigram index for the name search (pg_trgm is a trusted extension: the database owner may create it)
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------------------------------------------------
-- Categories (admin-managed, flat with an optional parent). The slug is the public id.
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE categories (
    id          uuid          PRIMARY KEY,
    slug        varchar(80)   NOT NULL,
    name        varchar(100)  NOT NULL,
    description varchar(1000),
    parent_id   uuid          REFERENCES categories (id),
    active      boolean       NOT NULL DEFAULT true,
    created_at  timestamptz   NOT NULL,
    updated_at  timestamptz   NOT NULL,
    version     bigint        NOT NULL DEFAULT 0,
    CONSTRAINT ux_categories_slug UNIQUE (slug),
    CONSTRAINT ck_categories_parent CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE UNIQUE INDEX ux_categories_name_ci ON categories (lower(name));
CREATE INDEX idx_categories_parent ON categories (parent_id);

-- ---------------------------------------------------------------------------------------------------------------
-- Products (items) and their options. store_id = the merchant's storeId from the JWT.
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE products (
    id           uuid          PRIMARY KEY,
    public_id    varchar(20)   NOT NULL,
    store_id     uuid          NOT NULL,
    name         varchar(200)  NOT NULL,
    description  varchar(5000),
    category_id  uuid          NOT NULL REFERENCES categories (id),
    status       varchar(16)   NOT NULL,
    cod_allowed  boolean       NOT NULL DEFAULT true,
    -- Aggregates of the visible reviews
    rating_avg   numeric(3,2)  NOT NULL DEFAULT 0,
    rating_count integer       NOT NULL DEFAULT 0,
    -- JSON array of keys in the (mock) image storage
    image_keys   text          NOT NULL DEFAULT '[]',
    created_at   timestamptz   NOT NULL,
    updated_at   timestamptz   NOT NULL,
    version      bigint        NOT NULL DEFAULT 0,
    CONSTRAINT ux_products_public_id UNIQUE (public_id),
    CONSTRAINT ck_products_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_products_rating CHECK (rating_avg >= 0 AND rating_avg <= 5 AND rating_count >= 0)
);
-- Merchant lists (always scoped by store) and the public search filters
CREATE INDEX idx_products_store_status ON products (store_id, status, created_at);
CREATE INDEX idx_products_category_status ON products (category_id, status);
CREATE INDEX idx_products_active_created ON products (created_at DESC) WHERE status = 'ACTIVE';
CREATE INDEX idx_products_active_rating ON products (rating_avg DESC, rating_count DESC) WHERE status = 'ACTIVE';
-- Name search: ILIKE '%q%' on lower(name) and similarity ranking
CREATE INDEX idx_products_name_trgm ON products USING gin (lower(name) gin_trgm_ops);

CREATE TABLE product_options (
    id            bigserial    PRIMARY KEY,
    product_id    uuid         NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    name          varchar(50)  NOT NULL,
    position      integer      NOT NULL,
    -- JSON array of the allowed values, e.g. ["S","M","L"]
    option_values text         NOT NULL
);
CREATE INDEX idx_product_options_product ON product_options (product_id);

-- ---------------------------------------------------------------------------------------------------------------
-- Variants: what is actually sold. Every product has at least one (a simple product has one default variant).
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE variants (
    id          uuid          PRIMARY KEY,
    public_id   varchar(20)   NOT NULL,
    product_id  uuid          NOT NULL REFERENCES products (id),
    -- Denormalised from the product: SKUs are unique per store, and every merchant query is scoped by store
    store_id    uuid          NOT NULL,
    sku         varchar(64)   NOT NULL,
    -- JSON object option name -> value, e.g. {"Size":"M","Colour":"Red"}; {} for a default variant
    attributes  text          NOT NULL DEFAULT '{}',
    list_price  numeric(12,2) NOT NULL,
    status      varchar(16)   NOT NULL,
    image_keys  text          NOT NULL DEFAULT '[]',
    position    integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL,
    updated_at  timestamptz   NOT NULL,
    version     bigint        NOT NULL DEFAULT 0,
    CONSTRAINT ux_variants_public_id UNIQUE (public_id),
    CONSTRAINT ck_variants_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_variants_price CHECK (list_price > 0)
);
-- The merchant's SKU is unique within the store, regardless of case (the item id is separate, D12)
CREATE UNIQUE INDEX ux_variants_store_sku ON variants (store_id, lower(sku));
CREATE INDEX idx_variants_product ON variants (product_id, position);
-- Price filter / sort of the search works on active variants
CREATE INDEX idx_variants_active_price ON variants (product_id, list_price) WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------------------------------------------------
-- Inventory, one row per variant.
--   quantity_available: units that can still be sold (held units are NOT included)
--   reserved_quantity : units held by HELD reservations (stock_reservations)
-- reserve: available -> reserved; commit: reserved -> gone; release: reserved -> available.
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE inventory (
    variant_id         uuid         PRIMARY KEY REFERENCES variants (id),
    quantity_available integer      NOT NULL DEFAULT 0,
    reserved_quantity  integer      NOT NULL DEFAULT 0,
    updated_at         timestamptz  NOT NULL,
    version            bigint       NOT NULL DEFAULT 0,
    -- Never negative: a reservation that would oversell fails instead (conditional update)
    CONSTRAINT ck_inventory_available CHECK (quantity_available >= 0),
    CONSTRAINT ck_inventory_reserved CHECK (reserved_quantity >= 0)
);

CREATE TABLE stock_reservations (
    id                 bigserial    PRIMARY KEY,
    -- order-service's reference for the order (e.g. its publicId); all lines of one order share it
    order_ref          varchar(64)  NOT NULL,
    variant_id         uuid         NOT NULL REFERENCES variants (id),
    -- Held (HELD) or taken (COMMITTED) units; 0 once a quote removed the line
    quantity           integer      NOT NULL,
    requested_quantity integer      NOT NULL,
    status             varchar(16)  NOT NULL,
    -- RELEASED only: RELEASED (by order-service), ADJUSTED (quote removed the line), EXPIRED (safety net)
    release_reason     varchar(16),
    expires_at         timestamptz  NOT NULL,
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    version            bigint       NOT NULL DEFAULT 0,
    CONSTRAINT ux_stock_reservations_order_variant UNIQUE (order_ref, variant_id),
    CONSTRAINT ck_stock_reservations_status CHECK (status IN ('HELD', 'COMMITTED', 'RELEASED')),
    CONSTRAINT ck_stock_reservations_quantity CHECK (quantity >= 0 AND requested_quantity > 0
        AND quantity <= requested_quantity),
    CONSTRAINT ck_stock_reservations_release CHECK ((status = 'RELEASED') = (release_reason IS NOT NULL))
);
-- Safety-net scheduler: HELD rows past their expiry
CREATE INDEX idx_stock_reservations_held ON stock_reservations (expires_at) WHERE status = 'HELD';

-- ---------------------------------------------------------------------------------------------------------------
-- Discounts (D13): product- or variant-wide, evaluated at read time; the single best one applies, no stacking.
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE discounts (
    id             uuid          PRIMARY KEY,
    public_id      varchar(20)   NOT NULL,
    store_id       uuid          NOT NULL,
    name           varchar(100),
    scope          varchar(16)   NOT NULL,
    product_id     uuid          NOT NULL REFERENCES products (id),
    variant_id     uuid          REFERENCES variants (id),
    discount_type  varchar(16)   NOT NULL,
    -- PERCENT: 1-90 (%), FIXED: LKR off the list price
    discount_value numeric(12,2) NOT NULL,
    starts_at      timestamptz,
    ends_at        timestamptz,
    active         boolean       NOT NULL DEFAULT true,
    created_at     timestamptz   NOT NULL,
    updated_at     timestamptz   NOT NULL,
    version        bigint        NOT NULL DEFAULT 0,
    CONSTRAINT ux_discounts_public_id UNIQUE (public_id),
    CONSTRAINT ck_discounts_scope CHECK ((scope = 'PRODUCT' AND variant_id IS NULL)
        OR (scope = 'VARIANT' AND variant_id IS NOT NULL)),
    CONSTRAINT ck_discounts_type CHECK (discount_type IN ('PERCENT', 'FIXED')),
    CONSTRAINT ck_discounts_value CHECK (discount_value > 0
        AND (discount_type <> 'PERCENT' OR discount_value BETWEEN 1 AND 90)),
    CONSTRAINT ck_discounts_window CHECK (starts_at IS NULL OR ends_at IS NULL OR ends_at > starts_at)
);
CREATE INDEX idx_discounts_product ON discounts (product_id) WHERE active;
CREATE INDEX idx_discounts_store ON discounts (store_id, created_at);

-- ---------------------------------------------------------------------------------------------------------------
-- Store visibility cache (event-fed): store-events StoreVisibilityChanged + the owner's status from user-events.
-- A store without a row is not visible (e.g. a pending merchant's drafts).
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE store_visibility (
    store_id        uuid          PRIMARY KEY,
    store_public_id varchar(20),
    store_name      varchar(100),
    -- published && acceptingOrders, as reported by store-service
    visible         boolean       NOT NULL DEFAULT false,
    cod_enabled     boolean       NOT NULL DEFAULT false,
    -- Owner's real status (incl. BAN_GRACE) from user-events; listings leave search unless ACTIVE (or unknown)
    owner_status    varchar(32),
    store_event_at  timestamptz,
    owner_event_at  timestamptz,
    updated_at      timestamptz   NOT NULL
);
CREATE UNIQUE INDEX ux_store_visibility_public_id ON store_visibility (store_public_id)
    WHERE store_public_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------------------------
-- Product reviews, merchant replies, eligibility (one row per completed order item, from OrderCompleted)
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE review_eligibility (
    id              bigserial    PRIMARY KEY,
    order_id        uuid         NOT NULL,
    order_public_id varchar(20)  NOT NULL,
    variant_id      uuid         NOT NULL REFERENCES variants (id),
    product_id      uuid         NOT NULL REFERENCES products (id),
    store_id        uuid         NOT NULL,
    customer_id     uuid         NOT NULL,
    completed_at    timestamptz  NOT NULL,
    CONSTRAINT ux_review_eligibility_item UNIQUE (order_id, variant_id)
);
CREATE INDEX idx_review_eligibility_customer ON review_eligibility (customer_id, product_id);

CREATE TABLE product_reviews (
    id              uuid          PRIMARY KEY,
    public_id       varchar(20)   NOT NULL,
    product_id      uuid          NOT NULL REFERENCES products (id),
    variant_id      uuid          NOT NULL REFERENCES variants (id),
    store_id        uuid          NOT NULL,
    order_id        uuid          NOT NULL,
    order_public_id varchar(20)   NOT NULL,
    customer_id     uuid          NOT NULL,
    rating          smallint      NOT NULL,
    body            varchar(2000),
    attachment_keys text          NOT NULL DEFAULT '[]',
    hidden          boolean       NOT NULL DEFAULT false,
    hidden_reason   varchar(500),
    hidden_by       varchar(40),
    hidden_at       timestamptz,
    created_at      timestamptz   NOT NULL,
    updated_at      timestamptz   NOT NULL,
    version         bigint        NOT NULL DEFAULT 0,
    CONSTRAINT ux_product_reviews_public_id UNIQUE (public_id),
    -- One review per order item
    CONSTRAINT ux_product_reviews_item UNIQUE (order_id, variant_id),
    CONSTRAINT fk_product_reviews_eligibility FOREIGN KEY (order_id, variant_id)
        REFERENCES review_eligibility (order_id, variant_id),
    CONSTRAINT ck_product_reviews_rating CHECK (rating BETWEEN 1 AND 5)
);
CREATE INDEX idx_product_reviews_product ON product_reviews (product_id, created_at) WHERE hidden = false;
CREATE INDEX idx_product_reviews_store ON product_reviews (store_id, created_at);

CREATE TABLE review_replies (
    id               uuid          PRIMARY KEY,
    review_id        uuid          NOT NULL REFERENCES product_reviews (id),
    store_id         uuid          NOT NULL,
    body             varchar(2000) NOT NULL,
    author_user_id   uuid          NOT NULL,
    author_public_id varchar(40),
    author_role      varchar(32)   NOT NULL,
    created_at       timestamptz   NOT NULL,
    -- One merchant reply per review
    CONSTRAINT ux_review_replies_review UNIQUE (review_id)
);

-- ---------------------------------------------------------------------------------------------------------------
-- Append-only audit log of privileged mutations (section 1). Sensitive fields are masked before insert.
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE audit_log (
    id              bigserial    PRIMARY KEY,
    occurred_at     timestamptz  NOT NULL,
    actor_id        uuid,
    actor_public_id varchar(40)  NOT NULL,
    actor_role      varchar(32)  NOT NULL,
    actor_store_id  uuid,
    action          varchar(64)  NOT NULL,
    target_type     varchar(32)  NOT NULL,
    target_id       varchar(64)  NOT NULL,
    before_state    text,
    after_state     text,
    reason          varchar(500)
);
CREATE INDEX idx_audit_log_target ON audit_log (target_type, target_id);
CREATE INDEX idx_audit_log_occurred_at ON audit_log (occurred_at);

CREATE FUNCTION audit_log_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_append_only();

-- ---------------------------------------------------------------------------------------------------------------
-- Transactional outbox (section 9) and consumer de-duplication
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE outbox (
    id           bigserial     PRIMARY KEY,
    event_id     uuid          NOT NULL,
    aggregate_id uuid          NOT NULL,
    event_type   varchar(64)   NOT NULL,
    topic        varchar(100)  NOT NULL,
    msg_key      varchar(100)  NOT NULL,
    payload      text          NOT NULL,
    created_at   timestamptz   NOT NULL,
    published_at timestamptz,
    attempts     integer       NOT NULL DEFAULT 0,
    last_error   varchar(1000),
    CONSTRAINT ux_outbox_event_id UNIQUE (event_id)
);
CREATE INDEX idx_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;

CREATE TABLE processed_events (
    event_id     uuid         NOT NULL,
    consumer     varchar(100) NOT NULL,
    processed_at timestamptz  NOT NULL,
    PRIMARY KEY (event_id, consumer)
);

-- ---------------------------------------------------------------------------------------------------------------
-- ShedLock (JdbcTemplateLockProvider, usingDbTime)
-- ---------------------------------------------------------------------------------------------------------------
CREATE TABLE shedlock (
    name       varchar(64)  PRIMARY KEY,
    lock_until timestamptz  NOT NULL,
    locked_at  timestamptz  NOT NULL,
    locked_by  varchar(255) NOT NULL
);
