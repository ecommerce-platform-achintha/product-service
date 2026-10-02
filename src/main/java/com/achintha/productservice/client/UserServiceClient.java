package com.achintha.productservice.client;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * user-service's internal endpoints, resolved through Eureka by its service id (no hardcoded URL). Call it through
 * {@link UserServiceGateway}, which adds the circuit breaker, retry and timeout.
 */
@FeignClient(name = "user-service", contextId = "userServiceClient")
public interface UserServiceClient {

    @PostMapping("/internal/auth/service-token")
    ServiceTokenResponse serviceToken(@RequestBody ServiceTokenRequest request);

    @GetMapping("/internal/users/{id}/security-state")
    SecurityStateResponse securityState(@PathVariable("id") UUID userId,
                                        @RequestHeader(HttpHeaders.AUTHORIZATION) String bearerServiceToken);

    record ServiceTokenRequest(String clientId, String clientSecret) {

        @Override
        public String toString() {
            return "ServiceTokenRequest[clientId=" + clientId + ", clientSecret=****]";
        }
    }

    record ServiceTokenResponse(String accessToken, String tokenType, long expiresIn, Instant expiresAt) {

        @Override
        public String toString() {
            return "ServiceTokenResponse[accessToken=****, expiresAt=" + expiresAt + "]";
        }
    }

    /** What user-service reports for a user: the real status (including {@code BAN_GRACE}) and token version. */
    record SecurityStateResponse(UUID userId, String publicId, String role, String status, String assistantStatus,
                                 long tv, UUID storeId, List<String> perms) {
    }
}
