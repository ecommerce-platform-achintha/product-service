package com.achintha.productservice.category;

import com.achintha.productservice.audit.AuditService;
import com.achintha.productservice.common.TextSanitizer;
import com.achintha.productservice.exception.BadRequestException;
import com.achintha.productservice.exception.ConflictException;
import com.achintha.productservice.exception.ErrorCode;
import com.achintha.productservice.exception.NotFoundException;
import com.achintha.productservice.product.ProductRepository;
import com.achintha.productservice.security.Actor;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Category master data: admin CRUD (audited), public list of the active ones. */
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final AuditService auditService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<CategoryResponse> publicList() {
        return categoryRepository.findAllOrdered().stream()
                .filter(Category::isUsable)
                .map(CategoryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> adminList() {
        return categoryRepository.findAllOrdered().stream().map(CategoryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public CategoryResponse get(String slug) {
        return CategoryResponse.from(require(slug));
    }

    @Transactional
    public CategoryResponse create(Actor actor, CategoryRequest request) {
        String slug = request.slug();
        String name = TextSanitizer.cleanLine(request.name());
        requireName(name);
        if (categoryRepository.existsBySlug(slug)) {
            throw slugTaken(slug);
        }
        if (categoryRepository.existsByNameIgnoreCase(name)) {
            throw nameTaken(name);
        }
        Instant now = clock.instant();
        Category category = new Category();
        category.setId(UUID.randomUUID());
        category.setSlug(slug);
        category.setName(name);
        category.setDescription(TextSanitizer.clean(request.description()));
        category.setParent(resolveParent(request.parentSlug(), category));
        category.setActive(request.active() == null || request.active());
        category.setCreatedAt(now);
        category.setUpdatedAt(now);
        category = save(category, slug, name);
        auditService.record(actor, "CATEGORY_CREATED", AuditService.TARGET_CATEGORY, slug, null, snapshot(category),
                null);
        return CategoryResponse.from(category);
    }

    @Transactional
    public CategoryResponse update(Actor actor, String currentSlug, CategoryRequest request) {
        Category category = require(currentSlug);
        Map<String, Object> before = snapshot(category);
        String slug = request.slug();
        String name = TextSanitizer.cleanLine(request.name());
        requireName(name);
        if (!slug.equals(category.getSlug()) && categoryRepository.existsBySlug(slug)) {
            throw slugTaken(slug);
        }
        if (categoryRepository.existsByNameIgnoreCaseAndIdNot(name, category.getId())) {
            throw nameTaken(name);
        }
        Category parent = resolveParent(request.parentSlug(), category);
        if (parent != null && categoryRepository.existsByParentId(category.getId())) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                    "A category with sub-categories cannot itself have a parent");
        }
        category.setSlug(slug);
        category.setName(name);
        category.setDescription(TextSanitizer.clean(request.description()));
        category.setParent(parent);
        category.setActive(request.active() == null || request.active());
        category.setUpdatedAt(clock.instant());
        category = save(category, slug, name);
        auditService.record(actor, "CATEGORY_UPDATED", AuditService.TARGET_CATEGORY, slug, before,
                snapshot(category), null);
        return CategoryResponse.from(category);
    }

    /** Only an unused category can be deleted; deactivate it otherwise. */
    @Transactional
    public void delete(Actor actor, String slug) {
        Category category = require(slug);
        if (categoryRepository.existsByParentId(category.getId())
                || productRepository.existsByCategoryId(category.getId())) {
            throw new ConflictException(ErrorCode.CATEGORY_IN_USE,
                    "Category '" + slug + "' has products or sub-categories; deactivate it instead");
        }
        Map<String, Object> before = snapshot(category);
        categoryRepository.delete(category);
        categoryRepository.flush();
        auditService.record(actor, "CATEGORY_DELETED", AuditService.TARGET_CATEGORY, slug, before, null, null);
    }

    /** The category a merchant files a product under: must exist and be active. */
    @Transactional(readOnly = true)
    public Category requireUsable(String slug) {
        Category category = categoryRepository.findBySlug(slug)
                .orElseThrow(() -> new BadRequestException(ErrorCode.CATEGORY_NOT_AVAILABLE,
                        "Unknown category '" + slug + "'"));
        if (!category.isUsable()) {
            throw new BadRequestException(ErrorCode.CATEGORY_NOT_AVAILABLE, "Category '" + slug + "' is not active");
        }
        return category;
    }

    private Category require(String slug) {
        return categoryRepository.findBySlug(slug)
                .orElseThrow(() -> new NotFoundException("Category not found: " + slug));
    }

    private Category resolveParent(String parentSlug, Category self) {
        if (parentSlug == null || parentSlug.isBlank()) {
            return null;
        }
        Category parent = categoryRepository.findBySlug(parentSlug)
                .orElseThrow(() -> new BadRequestException(ErrorCode.VALIDATION_FAILED,
                        "Unknown parent category '" + parentSlug + "'"));
        if (parent.getId().equals(self.getId()) || parent.getParent() != null) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                    "The parent must be another top-level category (two levels at most)");
        }
        return parent;
    }

    private Category save(Category category, String slug, String name) {
        try {
            return categoryRepository.saveAndFlush(category);
        } catch (DataIntegrityViolationException e) {
            // A concurrent request took the slug or the name after the checks above
            throw categoryRepository.existsByNameIgnoreCaseAndIdNot(name, category.getId())
                    ? nameTaken(name) : slugTaken(slug);
        }
    }

    private static void requireName(String name) {
        if (name == null) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "name must not be blank");
        }
    }

    private static Map<String, Object> snapshot(Category category) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("slug", category.getSlug());
        map.put("name", category.getName());
        map.put("parentSlug", category.getParent() == null ? null : category.getParent().getSlug());
        map.put("active", category.isActive());
        return map;
    }

    private static ConflictException slugTaken(String slug) {
        return new ConflictException(ErrorCode.CATEGORY_SLUG_TAKEN, "Category slug '" + slug + "' is already used");
    }

    private static ConflictException nameTaken(String name) {
        return new ConflictException(ErrorCode.CATEGORY_NAME_TAKEN, "Category '" + name + "' already exists");
    }
}
