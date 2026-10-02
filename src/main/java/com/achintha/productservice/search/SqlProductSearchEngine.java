package com.achintha.productservice.search;

import com.achintha.productservice.product.ProductFilter;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL search: escaped {@code ILIKE}-style match on the lower-cased name (trigram index), relevance by prefix
 * match then trigram similarity. The effective price of each active variant is computed in SQL with the same rules
 * as {@code PriceCalculator} (best single live discount that leaves a price above zero); a product's price is its
 * cheapest active variant. Only parameterised SQL; the optional parts are fixed fragments.
 */
@Component
@ConditionalOnProperty(name = "app.search.engine", havingValue = "sql", matchIfMissing = true)
public class SqlProductSearchEngine implements ProductSearchEngine {

    private static final String FROM = """
            from products p
            join store_visibility s on s.store_id = p.store_id
            join variants v on v.product_id = p.id and v.status = 'ACTIVE'
            left join inventory i on i.variant_id = v.id
            cross join lateral (
                select coalesce(max(a.amount), 0) as amount
                from (select case when d.discount_type = 'PERCENT'
                                  then round(v.list_price * d.discount_value / 100, 2)
                                  else d.discount_value end as amount
                      from discounts d
                      where d.product_id = v.product_id and d.active
                        and (d.scope = 'PRODUCT' or d.variant_id = v.id)
                        and (d.starts_at is null or d.starts_at <= :now)
                        and (d.ends_at is null or d.ends_at > :now)) a
                where a.amount > 0 and a.amount < v.list_price
            ) best
            where p.status = 'ACTIVE'
              and s.visible and (s.owner_status is null or s.owner_status = 'ACTIVE')
            """;

    private static final String PRICE = "min(v.list_price - best.amount)";

    private final NamedParameterJdbcTemplate jdbc;

    public SqlProductSearchEngine(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Page<UUID> search(ProductFilter filter, SearchSort sort, Pageable pageable, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource("now", Timestamp.from(now));
        StringBuilder where = new StringBuilder();
        String q = filter.q() == null ? null : filter.q().trim();
        boolean hasQuery = q != null && !q.isEmpty();
        if (hasQuery) {
            String escaped = escapeLike(q.toLowerCase(Locale.ROOT));
            where.append(" and lower(p.name) like :pattern escape '!'");
            params.addValue("pattern", "%" + escaped + "%");
            params.addValue("prefix", escaped + "%");
            params.addValue("text", q.toLowerCase(Locale.ROOT));
        }
        if (filter.categoryIds() != null) {
            where.append(" and p.category_id in (:categoryIds)");
            params.addValue("categoryIds", filter.categoryIds());
        }
        if (filter.storeId() != null) {
            where.append(" and p.store_id = :storeId");
            params.addValue("storeId", filter.storeId());
        }
        if (filter.minRating() != null) {
            where.append(" and p.rating_avg >= :minRating");
            params.addValue("minRating", filter.minRating());
        }
        StringBuilder having = new StringBuilder(" having true");
        if (filter.minPrice() != null) {
            having.append(" and ").append(PRICE).append(" >= :minPrice");
            params.addValue("minPrice", filter.minPrice());
        }
        if (filter.maxPrice() != null) {
            having.append(" and ").append(PRICE).append(" <= :maxPrice");
            params.addValue("maxPrice", filter.maxPrice());
        }
        if (filter.inStock()) {
            having.append(" and max(coalesce(i.quantity_available, 0)) > 0");
        }
        String grouped = FROM + where + " group by p.id" + having;

        Long total = jdbc.queryForObject("select count(*) from (select p.id " + grouped + ") x", params, Long.class);
        if (total == null || total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }
        params.addValue("limit", pageable.getPageSize());
        params.addValue("offset", pageable.getOffset());
        String sql = "select p.id " + grouped + " order by " + orderBy(sort, hasQuery)
                + " limit :limit offset :offset";
        List<UUID> ids = jdbc.queryForList(sql, params, UUID.class);
        return new PageImpl<>(ids, pageable, total);
    }

    /**
     * Escapes LIKE wildcards so user input such as "50%" is matched literally. Uses '!' as the escape character: a
     * backslash inside a quoted SQL literal is avoided on purpose.
     */
    static String escapeLike(String text) {
        return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static String orderBy(SearchSort sort, boolean hasQuery) {
        return switch (sort) {
            case RELEVANCE -> hasQuery
                    ? "(lower(p.name) like :prefix escape '!') desc, similarity(lower(p.name), :text) desc, "
                    + "p.created_at desc, p.id"
                    : "p.created_at desc, p.id";
            case PRICE_ASC -> PRICE + " asc, p.id";
            case PRICE_DESC -> PRICE + " desc, p.id";
            case NEWEST -> "p.created_at desc, p.id";
            case RATING -> "p.rating_avg desc, p.rating_count desc, p.id";
        };
    }
}
