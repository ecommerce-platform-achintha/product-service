package com.achintha.productservice.category;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    @EntityGraph(attributePaths = "parent")
    Optional<Category> findBySlug(String slug);

    boolean existsBySlug(String slug);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    boolean existsByParentId(UUID parentId);

    @EntityGraph(attributePaths = "parent")
    @Query("select c from Category c order by c.name")
    List<Category> findAllOrdered();

    /** Ids of the category and its direct children (search by a parent includes its sub-categories). */
    @Query("select c.id from Category c where c.id = :id or c.parent.id = :id")
    List<UUID> selfAndChildren(@Param("id") UUID id);

    @EntityGraph(attributePaths = "parent")
    List<Category> findAllByIdIn(Collection<UUID> ids);
}
