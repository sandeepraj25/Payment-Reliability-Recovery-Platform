package com.payguard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Phase 1 security model (hardened further in Phase 5):
 * <ul>
 *   <li>/api/webhooks/** : no login. Authenticity is proven by the HMAC signature instead,
 *       because the gateway cannot log in to our app.</li>
 *   <li>/sim/gateway/**  : the simulated gateway's API. The merchant backend calls it with
 *       its own client credentials (like a Razorpay key_id/key_secret).</li>
 *   <li>/api/**          : operator/admin API (HTTP Basic for now).</li>
 *   <li>everything else  : denied (secure default).</li>
 * </ul>
 * CSRF is disabled because this is a stateless JSON API with no browser session cookies;
 * CSRF attacks need an ambient cookie to ride on.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/webhooks/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/sim/gateway/**").hasAnyRole("GATEWAY_CLIENT", "ADMIN")
                        .requestMatchers("/api/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder(); // bcrypt by default
    }

    @Bean
    UserDetailsService userDetailsService(SecurityProperties props, PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
                User.withUsername(props.adminUsername())
                        .password(encoder.encode(props.adminPassword()))
                        .roles("ADMIN")
                        .build(),
                User.withUsername(props.gatewayClientId())
                        .password(encoder.encode(props.gatewayClientSecret()))
                        .roles("GATEWAY_CLIENT")
                        .build());
    }
}
