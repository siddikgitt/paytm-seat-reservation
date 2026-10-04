package com.paytm.seats.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.paytm.seats.api.ApiException;
import com.paytm.seats.config.AppProperties;
import com.paytm.seats.observability.RequestContext;

/**
 * Resolves {@link AuthUser} controller parameters from the {@code Authorization: Bearer} header.
 * Admin endpoints additionally accept the {@code X-Admin-Key} secret.
 */
@Component
public class AuthUserResolver implements HandlerMethodArgumentResolver {

    public static final String ADMIN_KEY_HEADER = "X-Admin-Key";

    private final TokenService tokens;
    private final byte[] adminKey;

    public AuthUserResolver(TokenService tokens, AppProperties props) {
        this.tokens = tokens;
        this.adminKey = props.adminApiKey().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(AuthUser.class);
    }

    @Override
    public AuthUser resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                    NativeWebRequest request, WebDataBinderFactory binderFactory) {
        boolean adminRequired = parameter.hasParameterAnnotation(Admin.class);
        String presentedKey = request.getHeader(ADMIN_KEY_HEADER);
        if (adminRequired && presentedKey != null && isAdminKey(presentedKey)) {
            RequestContext.user("admin");
            return new AuthUser("admin", true);
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw ApiException.unauthorized("missing bearer token");
        }
        AuthUser user = tokens.verify(header.substring(7).trim());
        RequestContext.user(user.userId());
        if (adminRequired && !user.admin()) {
            throw ApiException.forbidden("admin role required");
        }
        return user;
    }

    public boolean isAdminKey(String presented) {
        return MessageDigest.isEqual(adminKey, presented.getBytes(StandardCharsets.UTF_8));
    }
}
