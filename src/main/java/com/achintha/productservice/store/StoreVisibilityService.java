package com.achintha.productservice.store;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code store_visibility} cache. Updates are upserts guarded by the event time, so a late (out-of-order)
 * event never overwrites a newer state, and replaying an event changes nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreVisibilityService {

    private final StoreVisibilityRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    /** {@code StoreVisibilityChanged}: store-service's view ({@code visible = published && acceptingOrders}). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyStoreEvent(UUID storeId, String storePublicId, String storeName, boolean visible,
                                Boolean codEnabled, Instant occurredAt) {
        Instant at = occurredAt != null ? occurredAt : clock.instant();
        jdbcTemplate.update("""
                insert into store_visibility (store_id, store_public_id, store_name, visible, cod_enabled,
                                              store_event_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (store_id) do update set
                    store_public_id = coalesce(excluded.store_public_id, store_visibility.store_public_id),
                    store_name = coalesce(excluded.store_name, store_visibility.store_name),
                    visible = excluded.visible,
                    cod_enabled = excluded.cod_enabled,
                    store_event_at = excluded.store_event_at,
                    updated_at = excluded.updated_at
                where store_visibility.store_event_at is null or store_visibility.store_event_at <= excluded.store_event_at
                """, storeId, storePublicId, storeName, visible, codEnabled != null && codEnabled,
                Timestamp.from(at), Timestamp.from(clock.instant()));
    }

    /** The owner's {@code UserStatusChanged} (merchant): {@code BAN_GRACE} / {@code BANNED} hide the listings. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyOwnerStatus(UUID storeId, String ownerStatus, Instant occurredAt) {
        Instant at = occurredAt != null ? occurredAt : clock.instant();
        jdbcTemplate.update("""
                insert into store_visibility (store_id, visible, cod_enabled, owner_status, owner_event_at, updated_at)
                values (?, false, false, ?, ?, ?)
                on conflict (store_id) do update set
                    owner_status = excluded.owner_status,
                    owner_event_at = excluded.owner_event_at,
                    updated_at = excluded.updated_at
                where store_visibility.owner_event_at is null or store_visibility.owner_event_at <= excluded.owner_event_at
                """, storeId, ownerStatus, Timestamp.from(at), Timestamp.from(clock.instant()));
    }

    @Transactional(readOnly = true)
    public boolean isPubliclyVisible(UUID storeId) {
        return repository.findById(storeId).map(StoreVisibility::isPubliclyVisible).orElse(false);
    }

    @Transactional(readOnly = true)
    public Optional<StoreVisibility> find(UUID storeId) {
        return repository.findById(storeId);
    }

    /** Resolves a store's public id (search filter, admin moderation). */
    @Transactional(readOnly = true)
    public Optional<StoreVisibility> findByPublicId(String storePublicId) {
        return repository.findByStorePublicId(storePublicId);
    }

    @Transactional(readOnly = true)
    public Map<UUID, StoreVisibility> findAll(Collection<UUID> storeIds) {
        return storeIds.isEmpty() ? Map.of() : repository.findAllByStoreIdIn(storeIds).stream()
                .collect(Collectors.toMap(StoreVisibility::getStoreId, Function.identity()));
    }
}
