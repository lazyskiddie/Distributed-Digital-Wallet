package com.ledger.Wallet.Model;

import com.ledger.Wallet.Exception.ApiException;
import com.ledger.Wallet.Service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

 // Guards every /api/** request:
 // 1. Anything that changes data must carry the X-Requested-With header (a browser cannot add it to
// a cross-site form post, which blocks CSRF on top of the SameSite cookie).
 // 2. Everything except sign-up / sign-in / sign-out requires a valid session.
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    public static final String USER_ATTR = "currentUser";
    public static final String CSRF_HEADER = "X-Requested-With";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/logout");

    private final SessionService sessions;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        response.setHeader("Cache-Control", "no-store"); // personal data must not be cached

        if (!SAFE_METHODS.contains(request.getMethod()) && request.getHeader(CSRF_HEADER) == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Missing required request header.");
        }
        if (PUBLIC_PATHS.contains(request.getRequestURI())) {
            return true;
        }
        AppUser user = sessions.resolve(SessionCookies.read(request))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Please sign in to continue."));
        request.setAttribute(USER_ATTR, user);
        return true;
    }
}
