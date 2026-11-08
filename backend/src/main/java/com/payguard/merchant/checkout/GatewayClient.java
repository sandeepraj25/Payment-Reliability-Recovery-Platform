package com.payguard.merchant.checkout;

import com.payguard.common.error.ApiException;
import com.payguard.config.SecurityProperties;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * The merchant's HTTP client for the payment gateway (like a Razorpay/Stripe SDK).
 *
 * <p>It talks to the simulator over real HTTP even though both run in one JVM, so the
 * failure modes are real: timeouts, connection refused, 5xx. A modular monolith keeps
 * deployment simple; the HTTP boundary keeps the reasoning honest.
 */
@Component
public class GatewayClient {

    private final RestClient restClient;
    private final Environment environment;
    private final String clientId;
    private final String clientSecret;

    public GatewayClient(RestClient.Builder builder, Environment environment, SecurityProperties security,
                         GatewayClientProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeout());
        factory.setReadTimeout(props.readTimeout());
        this.restClient = builder.requestFactory(factory).build();
        this.environment = environment;
        this.clientId = security.gatewayClientId();
        this.clientSecret = security.gatewayClientSecret();
    }

    /** Subset of the gateway's payment view that the merchant needs. Unknown JSON fields are ignored. */
    public record GatewayPaymentDto(String id, String gatewayReference, String merchantOrderRef,
                                    long amountMinor, String currency, String status, long version) {
    }

    /**
     * Creates (or, for a repeated key, returns the existing) gateway payment.
     *
     * <p>A timeout here is AMBIGUOUS: the gateway may or may not have created the payment.
     * That is safe only because the caller always uses the same idempotency key for the same
     * merchant payment, so retrying can never create a second gateway payment.
     */
    public GatewayPaymentDto createPayment(String idempotencyKey, String merchantOrderRef, long amountMinor, String currency) {
        // Resolved per call: ${local.server.port} only exists after the web server has started.
        String baseUrl = environment.getRequiredProperty("payguard.gateway.base-url");
        try {
            return restClient.post()
                    .uri(baseUrl + "/payments")
                    .headers(h -> h.setBasicAuth(clientId, clientSecret))
                    .header("Idempotency-Key", idempotencyKey)
                    .body(Map.of("merchantOrderRef", merchantOrderRef, "amountMinor", amountMinor, "currency", currency))
                    .retrieve()
                    .body(GatewayPaymentDto.class);
        } catch (ResourceAccessException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GATEWAY_UNAVAILABLE",
                    "Payment gateway did not respond; retry with the same Idempotency-Key");
        } catch (RestClientResponseException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GATEWAY_ERROR",
                    "Payment gateway returned HTTP " + e.getStatusCode().value());
        }
    }
}
