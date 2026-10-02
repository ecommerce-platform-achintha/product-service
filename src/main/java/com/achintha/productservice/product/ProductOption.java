package com.achintha.productservice.product;

import com.achintha.productservice.common.StringListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A product option such as Size or Colour, with its allowed values. Variant attributes must use them. */
@Entity
@Table(name = "product_options")
@Getter
@Setter
@NoArgsConstructor
public class ProductOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false)
    private int position;

    @Convert(converter = StringListConverter.class)
    @Column(name = "option_values", nullable = false, columnDefinition = "text")
    private List<String> values = new ArrayList<>();
}
