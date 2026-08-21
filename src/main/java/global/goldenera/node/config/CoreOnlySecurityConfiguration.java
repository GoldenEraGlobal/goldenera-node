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
package global.goldenera.node.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import global.goldenera.node.shared.filters.ThrottlingFilter;
import global.goldenera.node.shared.properties.SecurityProperties;

/** SQL-free HTTP security graph used by explicitly configured core-only nodes. */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@ConditionalOnProperty(
			prefix = "ge.general",
			name = "postgresql-enable",
			havingValue = "false")
public class CoreOnlySecurityConfiguration {

	private final SecurityProperties securityProperties;

	public CoreOnlySecurityConfiguration(SecurityProperties securityProperties) {
		this.securityProperties = securityProperties;
	}

	@Bean
	@Order(0)
	SecurityFilterChain coreOnlyHealthFilterChain(HttpSecurity http) throws Exception {
		http.securityMatcher("/api/core/v1/health/**")
				.cors(Customizer.withDefaults())
				.csrf(csrf -> csrf.disable())
				.formLogin(login -> login.disable())
				.httpBasic(basic -> basic.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
		return http.build();
	}

	@Bean
	@Order(1)
	SecurityFilterChain coreOnlyAdminApiFilterChain(HttpSecurity http) throws Exception {
		http.securityMatcher("/api/admin/**", "/api/shared/**", "/api/explorer/**", "/api/bridge/**")
				.cors(Customizer.withDefaults())
				.csrf(csrf -> csrf.disable())
				.formLogin(login -> login.disable())
				.httpBasic(basic -> basic.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth.anyRequest().denyAll());
		return http.build();
	}

	@Bean
	@Order(2)
	SecurityFilterChain coreOnlyCoreApiFilterChain(
			HttpSecurity http,
			ThrottlingFilter throttlingFilter) throws Exception {
		http.securityMatcher("/api/core/**")
				.cors(Customizer.withDefaults())
				.csrf(csrf -> csrf.disable())
				.formLogin(login -> login.disable())
				.httpBasic(basic -> basic.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> {
					auth.requestMatchers(HttpMethod.GET,
							"/api/core/v1/sync/snapshots/checkpoint/**").permitAll();
					if (securityProperties.isCoreApiEnabled()) {
						auth.anyRequest().denyAll();
					} else {
						auth.anyRequest().permitAll();
					}
				})
				.addFilterBefore(throttlingFilter, UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}
}
