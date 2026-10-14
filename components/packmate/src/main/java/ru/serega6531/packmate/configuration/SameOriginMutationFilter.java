package ru.serega6531.packmate.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

final class SameOriginMutationFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean apiMutation = path.startsWith("/api/")
                && !SAFE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT));
        boolean websocketHandshake = (path.equals("/api/ws") || path.startsWith("/api/ws/"))
                && "websocket".equalsIgnoreCase(request.getHeader("Upgrade"));
        if ((apiMutation || websocketHandshake)
                && !isSameOrigin(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"message\":\"Источник запроса не совпадает с адресом Packmate.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isSameOrigin(HttpServletRequest request) {
        String originHeader = request.getHeader("Origin");
        if (originHeader == null || originHeader.isBlank()) {
            return false;
        }

        try {
            URI origin = new URI(originHeader);
            String scheme = request.getScheme();
            if (!origin.isAbsolute()
                    || origin.getHost() == null
                    || origin.getUserInfo() != null
                    || origin.getRawQuery() != null
                    || origin.getRawFragment() != null
                    || (origin.getRawPath() != null && !origin.getRawPath().isEmpty())
                    || scheme == null
                    || !origin.getScheme().equalsIgnoreCase(scheme)
                    || !origin.getHost().equalsIgnoreCase(request.getServerName())) {
                return false;
            }

            int originPort = origin.getPort() == -1 ? defaultPort(origin.getScheme()) : origin.getPort();
            return originPort == request.getServerPort();
        } catch (URISyntaxException | IllegalArgumentException exception) {
            return false;
        }
    }

    private int defaultPort(String scheme) {
        if ("http".equalsIgnoreCase(scheme)) {
            return 80;
        }
        if ("https".equalsIgnoreCase(scheme)) {
            return 443;
        }
        return -1;
    }
}
