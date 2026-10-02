package com.achintha.productservice.store;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreVisibilityRepository extends JpaRepository<StoreVisibility, UUID> {

    Optional<StoreVisibility> findByStorePublicId(String storePublicId);

    List<StoreVisibility> findAllByStoreIdIn(Collection<UUID> storeIds);
}
