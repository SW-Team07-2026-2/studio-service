package com.indie.studio.global.client;

import com.indie.studio.global.config.StudioProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * 나가는 X-Internal-Token (HS256 · 공유 시크릿) — {iss: studio, aud: <대상 서비스>, exp: now+300}. 다른 서비스와 같은 규약입니다.
 * 매 호출마다 새로 만듭니다 (캐시하지 않음).
 */
@Component
public class InternalTokenIssuer {

    private final MACSigner signer;
    private final StudioProperties.InternalToken props;
    private final Clock clock;

    public InternalTokenIssuer(@Value("${internal.shared-secret}") String sharedSecret, StudioProperties props, Clock clock)
            throws JOSEException {
        this.signer = new MACSigner(sharedSecret.trim().getBytes(StandardCharsets.UTF_8));   // 256bit 미만이면 기동 실패
        this.props = props.internalToken();
        this.clock = clock;
    }

    public String issue(String audience) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(props.issuer())
                .audience(audience)
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(props.ttlSeconds())))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("internal token signing failed", e);
        }
        return jwt.serialize();
    }
}
