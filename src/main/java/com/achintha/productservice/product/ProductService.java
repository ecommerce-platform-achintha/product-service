package com.achintha.productservice.product;

import com.achintha.productservice.category.Category;
import com.achintha.productservice.category.CategoryService;
import com.achintha.productservice.common.PageResponse;
import com.achintha.productservice.exception.BadRequestException;
import com.achintha.productservice.exception.ConflictException;
import com.achintha.productservice.exception.NotFoundException;
import com.achintha.productservice.inventory.Inventory;
import com.achintha.productservice.inventory.InventoryRepository;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductService {

    /** Fields clients may sort by; anything else is rejected with 400 instead of failing deep in JPA. */
    static final Set<String> SORTABLE_FIELDS = Set.of("name", "price", "sku", "createdAt", "updatedAt");

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final CategoryService categoryService;

    @Transactional
    public ProductResponse create(ProductRequest request) {
        String sku = request.sku().trim();
        if (productRepository.existsBySku(sku)) {
            throw duplicateSku(sku);
        }

        Product product = new Product();
        apply(product, request, sku);
        try {
            product = productRepository.saveAndFlush(product);
        } catch (DataIntegrityViolationException e) {
            // Concurrent create with the same SKU slipped past the exists check
            throw duplicateSku(sku);
        }

        Inventory inventory = inventoryRepository.save(new Inventory(product));
        return ProductResponse.from(product, inventory);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(UUID id) {
        Product product = findProduct(id);
        return ProductResponse.from(product, inventoryRepository.findByProductId(id).orElse(null));
    }

    @Transactional
    public ProductResponse update(UUID id, ProductRequest request) {
        Product product = findProduct(id);
        String sku = request.sku().trim();
        if (productRepository.existsBySkuAndIdNot(sku, id)) {
            throw duplicateSku(sku);
        }

        apply(product, request, sku);
        try {
            productRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw duplicateSku(sku);
        }
        return ProductResponse.from(product, inventoryRepository.findByProductId(id).orElse(null));
    }

    @Transactional
    public void delete(UUID id) {
        Product product = findProduct(id);
        inventoryRepository.deleteByProductId(id);
        productRepository.delete(product);
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> search(ProductFilter filter, Pageable pageable) {
        if (filter.minPrice() != null && filter.maxPrice() != null
                && filter.minPrice().compareTo(filter.maxPrice()) > 0) {
            throw new BadRequestException("minPrice must not be greater than maxPrice");
        }

        Page<Product> page = productRepository.findAll(ProductSpecifications.fromFilter(filter),
                withStableSort(pageable));

        // One query for the whole page's stock rather than one per product
        Map<UUID, Inventory> stockByProduct = inventoryRepository
                .findByProductIdIn(page.map(Product::getId).getContent()).stream()
                .collect(Collectors.toMap(inv -> inv.getProduct().getId(), Function.identity()));

        return PageResponse.from(page.map(p -> ProductResponse.from(p, stockByProduct.get(p.getId()))));
    }

    /** Validates the requested sort and appends id as a tie-breaker so paging is deterministic. */
    static Pageable withStableSort(Pageable pageable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!SORTABLE_FIELDS.contains(order.getProperty())) {
                throw new BadRequestException("Cannot sort by '" + order.getProperty() + "'. Allowed: "
                        + SORTABLE_FIELDS.stream().sorted().collect(Collectors.joining(", ")));
            }
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort().and(Sort.by("id")));
    }

    private void apply(Product product, ProductRequest request, String sku) {
        Category category = categoryService.findCategory(request.categoryId());
        product.setName(request.name().trim());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setSku(sku);
        product.setCategory(category);
    }

    private Product findProduct(UUID id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product not found: " + id));
    }

    private static ConflictException duplicateSku(String sku) {
        return new ConflictException("A product with SKU '" + sku + "' already exists");
    }
}
