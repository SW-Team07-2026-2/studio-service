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

/** Studio -> 대상 서비스 호출에 사용할 HS256 내부 토큰을 요청마다 발급합니다. */
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

    /**
     * 대상 서비스 호출용 HS256 내부 토큰을 발급합니다.
     *
     * @param audience 내부 토큰을 받을 서비스
     */
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
