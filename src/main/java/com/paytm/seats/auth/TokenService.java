package com.paytm.seats.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;

import org.springframework.stereotype.Service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.paytm.seats.api.ApiException;
import com.paytm.seats.config.AppProperties;

/** HS256 tokens. The subject is the only source of user identity in the service. */
@Service
public class TokenService {

    private static final String ISSUER = "seat-reservation";
    private static final String ROLE_CLAIM = "role";

    private final AppProperties props;
    private final MACSigner signer;
    private final MACVerifier verifier;

    public TokenService(AppProperties props) throws JOSEException {
        this.props = props;
        byte[] key = sha256(props.jwtSecret());
        this.signer = new MACSigner(key);
        this.verifier = new MACVerifier(key);
    }

    public String issue(String userId, boolean admin) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(userId)
                .claim(ROLE_CLAIM, admin ? "admin" : "user")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(props.tokenTtl())))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("token signing failed", e);
        }
        return jwt.serialize();
    }

    public AuthUser verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(verifier)) {
                throw ApiException.unauthorized("invalid token signature");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date exp = claims.getExpirationTime();
            if (!ISSUER.equals(claims.getIssuer()) || exp == null || exp.before(new Date())
                    || claims.getSubject() == null) {
                throw ApiException.unauthorized("token expired or invalid");
            }
            return new AuthUser(claims.getSubject(), "admin".equals(claims.getStringClaim(ROLE_CLAIM)));
        } catch (ParseException | JOSEException e) {
            throw ApiException.unauthorized("malformed token");
        }
    }

    public long ttlSeconds() {
        return props.tokenTtl().toSeconds();
    }

    private static byte[] sha256(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
