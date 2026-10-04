package com.paytm.seats.auth;

import java.util.regex.Pattern;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.paytm.seats.api.ApiException;

/**
 * Stand-in identity provider so testers can mint tokens for many users.
 * In production this is replaced by the real IdP; the rest of the service only trusts verified tokens.
 */
@RestController
public class AuthController {

    private static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9_.:@-]{1,64}");

    private final TokenService tokens;
    private final AuthUserResolver resolver;

    public AuthController(TokenService tokens, AuthUserResolver resolver) {
        this.tokens = tokens;
        this.resolver = resolver;
    }

    public record TokenRequest(String userId, String role) {
    }

    public record TokenResponse(String token, String userId, String role, long expiresIn) {
    }

    @PostMapping("/auth/token")
    public TokenResponse issue(@RequestBody TokenRequest body,
                               @RequestHeader(value = AuthUserResolver.ADMIN_KEY_HEADER, required = false) String adminKey) {
        if (body == null || body.userId() == null || !USER_ID.matcher(body.userId()).matches()) {
            throw ApiException.badRequest("invalid_request", "user_id must match " + USER_ID.pattern());
        }
        boolean admin = "admin".equals(body.role());
        if (admin && (adminKey == null || !resolver.isAdminKey(adminKey))) {
            throw ApiException.forbidden("admin tokens require a valid X-Admin-Key");
        }
        return new TokenResponse(tokens.issue(body.userId(), admin), body.userId(), admin ? "admin" : "user",
                tokens.ttlSeconds());
    }
}
