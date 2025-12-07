/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2025-2030 The GoldenEraGlobal Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package global.goldenera.node.shared.config;

import static lombok.AccessLevel.PRIVATE;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import global.goldenera.node.shared.components.HmacComponent;
import global.goldenera.node.shared.filters.ApiKeyAuthFilter;
import global.goldenera.node.shared.filters.ThrottlingFilter;
import global.goldenera.node.shared.properties.GeneralProperties;
import global.goldenera.node.shared.services.ApiKeyCoreService;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@Configuration
@EnableWebSecurity
@AllArgsConstructor
@EnableMethodSecurity(prePostEnabled = true)
@FieldDefaults(level = PRIVATE, makeFinal = true)
@Slf4j
public class SecurityConfig {

        GeneralProperties generalProperties;

        // generalProperties.isExplorerEnable() <----

        /**
         * Filter chain for the "master password" admin area.
         * This uses HTTP Basic Auth.
         */
        @Bean
        @Order(1)
        public SecurityFilterChain adminApiFilterChain(HttpSecurity http) throws Exception {
                http
                                .securityMatcher("/api/admin/**")
                                .cors(Customizer.withDefaults())
                                .csrf(csrf -> csrf.disable())
                                .formLogin(login -> login.disable())
                                .httpBasic(Customizer.withDefaults())
                                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"));
                return http.build();
        }

        /**
         * Filter chain for the explorer and shared API, protected by API Keys.
         * <p>
         * If Explorer is disabled via properties, access to /api/explorer/** is
         * strictly denied.
         */
        @Bean
        @Order(2)
        public SecurityFilterChain explorerAndSharedApiFilterChain(HttpSecurity http, ApiKeyAuthFilter apiKeyAuthFilter,
                        ThrottlingFilter throttlingFilter)
                        throws Exception {
                http
                                .securityMatcher("/api/explorer/**", "/api/shared/**")
                                .cors(Customizer.withDefaults())
                                .csrf(csrf -> csrf.disable())
                                .httpBasic(basic -> basic.disable())
                                .formLogin(login -> login.disable());

                http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                .authorizeHttpRequests(auth -> {
                                        if (!generalProperties.isExplorerEnable()) {
                                                log.debug("Explorer API is disabled in properties. Blocking access to /api/explorer/**");
                                                auth.requestMatchers("/api/explorer/**").denyAll();
                                        }
                                        auth.anyRequest().authenticated();
                                })
                                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                                .addFilterAfter(throttlingFilter, ApiKeyAuthFilter.class);
                return http.build();
        }

        /**
         * Filter chain for the CORE API
         */
        @Bean
        @Order(3)
        public SecurityFilterChain coreApiFilterChain(HttpSecurity http, ApiKeyAuthFilter apiKeyAuthFilter,
                        ThrottlingFilter throttlingFilter) throws Exception {
                http
                                .securityMatcher("/api/core/**")
                                .cors(Customizer.withDefaults())
                                .authorizeHttpRequests(auth -> auth
                                                .anyRequest().permitAll())
                                .csrf(csrf -> csrf.disable())
                                .formLogin(login -> login.disable())
                                .httpBasic(basic -> basic.disable());

                http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                                .addFilterAfter(throttlingFilter, ApiKeyAuthFilter.class);
                return http.build();
        }

        @Bean
        @Primary
        public PasswordEncoder passwordEncoder() {
                return PasswordEncoderFactories.createDelegatingPasswordEncoder();
        }

        @Bean
        public ApiKeyAuthFilter apiKeyAuthFilter(ApiKeyCoreService apiKeyCoreService, HmacComponent hmacComponent,
                        ObjectMapper objectMapper) {
                return new ApiKeyAuthFilter(apiKeyCoreService, hmacComponent, objectMapper);
        }

        @Bean
        CorsConfigurationSource corsConfigurationSource() {
                CorsConfiguration configuration = new CorsConfiguration();
                configuration.setAllowedOriginPatterns(List.of("*"));
                configuration.setAllowedMethods(List.of("*"));
                configuration.setAllowedHeaders(List.of("*"));
                configuration.setAllowCredentials(true);
                configuration.setMaxAge(3600L);

                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
                source.registerCorsConfiguration("/**", configuration);
                return source;
        }
}
