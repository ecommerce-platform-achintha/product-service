package com.achintha.productservice.outbox;

/** Events on the {@code product-events} topic (section 9). The enum name is the wire {@code eventType}. */
public enum ProductEventType {
    /** A product was created, edited (incl. variants), or changed status. */
    ProductChanged
}
