package com.achintha.productservice.category;

import com.achintha.productservice.config.OpenApiConfig;
import com.achintha.productservice.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Categories: public list of the active ones; CRUD for admins (ROLE_ADMIN or ROLE_SUPER_ADMIN). */
@RestController
@RequiredArgsConstructor
@Tag(name = "Categories")
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping("/api/public/categories")
    @Operation(summary = "Active categories (flat list; parentSlug links a sub-category to its parent)")
    public List<CategoryResponse> publicList() {
        return categoryService.publicList();
    }

    @GetMapping("/api/admin/categories")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SUPER_ADMIN')")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @Operation(summary = "All categories, inactive ones included")
    public List<CategoryResponse> adminList() {
        return categoryService.adminList();
    }

    @GetMapping("/api/admin/categories/{slug}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SUPER_ADMIN')")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    public CategoryResponse get(@PathVariable String slug) {
        return categoryService.get(slug);
    }

    @PostMapping("/api/admin/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SUPER_ADMIN')")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @Operation(summary = "Create a category (unique slug and name)")
    public CategoryResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CategoryRequest request) {
        return categoryService.create(Actor.from(jwt), request);
    }

    @PutMapping("/api/admin/categories/{slug}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SUPER_ADMIN')")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @Operation(summary = "Update a category (slug, name, parent, active)")
    public CategoryResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable String slug,
                                   @Valid @RequestBody CategoryRequest request) {
        return categoryService.update(Actor.from(jwt), slug, request);
    }

    @DeleteMapping("/api/admin/categories/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SUPER_ADMIN')")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @Operation(summary = "Delete an unused category (409 CATEGORY_IN_USE otherwise: deactivate it instead)")
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String slug) {
        categoryService.delete(Actor.from(jwt), slug);
    }
}
