package com.example.accounting.shared.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;

/** Keeps local API routing available; JWT resource-server wiring is added when an issuer is configured. */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}")
    private String issuerUri;

    @Value("${app.security.local-user-header-enabled:false}")
    private boolean localUserHeaderEnabled;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/actuator/**").permitAll();
                    if (StringUtils.hasText(issuerUri)) {
                        auth.requestMatchers("/v1/**").authenticated();
                    } else {
                        auth.requestMatchers("/v1/**").permitAll();
                    }
                    auth.anyRequest().permitAll();
                })
                .addFilterBefore(new LocalUserHeaderAuthenticationFilter(
                        localUserHeaderEnabled && !StringUtils.hasText(issuerUri)),
                        AnonymousAuthenticationFilter.class);
        if (StringUtils.hasText(issuerUri)) {
            http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> { }));
        }
        return http.build();
    }
}
