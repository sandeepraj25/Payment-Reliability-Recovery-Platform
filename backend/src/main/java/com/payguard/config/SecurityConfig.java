package com.payguard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Security model.
 * <ul>
 *   <li>/api/webhooks/** POST : no login - authenticity is the HMAC signature (the gateway cannot log in).</li>
 *   <li>/sim/gateway/**       : the simulated gateway's API, called with the merchant's client credentials.</li>
 *   <li>GET /api/**           : ADMIN or VIEWER (read-only dashboard users).</li>
 *   <li>other /api/**         : ADMIN only (replays, resolutions, simulations, checkout).</li>
 *   <li>/actuator/prometheus  : ADMIN (metrics reveal business volumes).</li>
 *   <li>everything else       : denied (secure default).</li>
 * </ul>
 * Why roles here: the only meaningful split in an ops tool is "can look" vs "can change money-related
 * state". More roles would be decoration.
 *
 * CSRF is disabled because this is a stateless JSON API authenticated with an Authorization header,
 * never with an ambient cookie - CSRF needs a cookie the browser attaches automatically.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, RateLimitFilter rateLimit) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(rateLimit, BasicAuthenticationFilter.class)
                .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/webhooks/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/prometheus", "/actuator/metrics/**").hasRole("ADMIN")
                        .requestMatchers("/sim/gateway/**").hasAnyRole("GATEWAY_CLIENT", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("ADMIN", "VIEWER")
                        .requestMatchers("/api/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    /** The filter runs inside the security chain only; stop Boot from also adding it to the servlet chain. */
    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<RateLimitFilter> rateLimitRegistration(RateLimitFilter f) {
        var reg = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(f);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties props) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.corsOrigins());     // explicit list - never "*" with credentials
        cors.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id"));
        cors.setExposedHeaders(List.of("X-Correlation-Id", "Idempotent-Replayed", "Retry-After"));
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder(); // bcrypt by default
    }

    @Bean
    UserDetailsService userDetailsService(SecurityProperties props, PasswordEncoder encoder) {
        List<UserDetails> users = new ArrayList<>();
        users.add(User.withUsername(props.adminUsername()).password(encoder.encode(props.adminPassword())).roles("ADMIN").build());
        users.add(User.withUsername(props.gatewayClientId()).password(encoder.encode(props.gatewayClientSecret())).roles("GATEWAY_CLIENT").build());
        if (props.viewerEnabled()) {
            users.add(User.withUsername(props.viewerUsername() == null ? "viewer" : props.viewerUsername())
                    .password(encoder.encode(props.viewerPassword())).roles("VIEWER").build());
        }
        return new InMemoryUserDetailsManager(users);
    }
}
