package com.achintha.productservice.common;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A mandatory reason (moderation, credits, setting changes); sanitized before it is stored. */
public record ReasonRequest(@NotBlank @Size(max = Validation.REASON_MAX) String reason) {
}
