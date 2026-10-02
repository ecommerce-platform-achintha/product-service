package com.achintha.productservice.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/** Public API docs. {@code /internal/**} is excluded ({@code springdoc.paths-to-exclude}). */
@Configuration
@OpenAPIDefinition(info = @Info(title = "Product Service API", version = "v1",
        description = "Catalog of the marketplace: categories, merchant-owned products (items) with variants, stock, "
                + "discounts, public search and product reviews. Public APIs use publicIds only (ITM-, VAR-, REV-, "
                + "DSC-; categories by slug). Errors carry a stable 'code'."))
@SecurityScheme(name = OpenApiConfig.BEARER_AUTH, type = SecuritySchemeType.HTTP, scheme = "bearer",
        bearerFormat = "JWT")
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";
}
