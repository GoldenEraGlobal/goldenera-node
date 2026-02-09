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
package global.goldenera.node.shared.security;

import static lombok.AccessLevel.PRIVATE;

import java.lang.reflect.Method;
import java.util.Collection;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import global.goldenera.node.shared.enums.ApiKeyPermission;
import global.goldenera.node.shared.exceptions.GEAuthenticationException;
import global.goldenera.node.shared.properties.SecurityProperties;
import lombok.AllArgsConstructor;
import lombok.experimental.FieldDefaults;

@Aspect
@Component
@FieldDefaults(level = PRIVATE, makeFinal = true)
@AllArgsConstructor
public class ExplorerApiSecurityAspect {

    SecurityProperties securityProperties;

    @Before("@within(global.goldenera.node.shared.security.ExplorerApiSecurity) || @annotation(global.goldenera.node.shared.security.ExplorerApiSecurity)")
    public void checkSecurity(JoinPoint joinPoint) {
        if (!securityProperties.isExplorerApiEnabled()) {
            return;
        }

        ExplorerApiSecurity explorerApiSecurity = getAnnotation(joinPoint);
        if (explorerApiSecurity == null) {
            return;
        }

        ApiKeyPermission requiredPermission = explorerApiSecurity.value();
        String requiredAuthorityString = requiredPermission.getAuthority();

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new GEAuthenticationException("Explorer API Access: User is not authenticated.");
        }

        boolean hasAuthority = false;
        Collection<? extends GrantedAuthority> userAuthorities = authentication.getAuthorities();

        for (GrantedAuthority auth : userAuthorities) {
            if (auth.getAuthority().equals(requiredAuthorityString)) {
                hasAuthority = true;
                break;
            }
        }

        if (!hasAuthority) {
            throw new GEAuthenticationException(
                    "Explorer API Access Denied: Missing required permission " + requiredPermission);
        }
    }

    private ExplorerApiSecurity getAnnotation(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        ExplorerApiSecurity annotation = AnnotationUtils.findAnnotation(method, ExplorerApiSecurity.class);
        if (annotation == null) {
            annotation = AnnotationUtils.findAnnotation(method.getDeclaringClass(), ExplorerApiSecurity.class);
        }
        return annotation;
    }
}
