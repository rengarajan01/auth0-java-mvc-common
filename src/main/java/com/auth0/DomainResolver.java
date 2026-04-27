package com.auth0;

import javax.servlet.http.HttpServletRequest;

/**
 * Resolves the Auth0 domain dynamically from the incoming HTTP request.
 * Implement this interface when you have multiple custom domains or need
 * to select a tenant domain based on the request context.
 *
 * @example Route requests to different tenants by host header
 * DomainResolver resolver = request -> {
 *     String host = request.getServerName();
 *     if (host.endsWith(".us.example.com")) return "tenant-us.auth0.com";
 *     return "tenant-eu.auth0.com";
 * };
 * AuthenticationController controller = AuthenticationController
 *     .newBuilder(resolver, "YOUR_CLIENT_ID", "YOUR_CLIENT_SECRET")
 *     .build();
 */
public interface DomainResolver {
    /**
     * Resolves the domain to be used for the current request.
     *
     * @param request the current HttpServletRequest
     * @return a single domain string (e.g., "tenant.auth0.com")
     * @example Return different domains based on request host
     * public String resolve(HttpServletRequest request) {
     *     return request.getServerName().endsWith(".us.example.com")
     *         ? "tenant-us.auth0.com"
     *         : "tenant-eu.auth0.com";
     * }
     */
    String resolve(HttpServletRequest request);
}
