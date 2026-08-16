package com.eniglio.ragplatform.auth.controller;

import com.eniglio.ragplatform.auth.security.JwtKeyProvider;
import com.nimbusds.jose.jwk.JWKSet;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Tag(name = "JWKS", description = "Public key set the other services use to validate tokens issued by this one")
public class JwksController {

    private final JwtKeyProvider jwtKeyProvider;

    public JwksController(JwtKeyProvider jwtKeyProvider) {
        this.jwtKeyProvider = jwtKeyProvider;
    }

    @Operation(summary = "JWKS endpoint",
            description = "Standard JWK Set — only the public key, never the private one used to sign tokens")
    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return new JWKSet(jwtKeyProvider.publicJwk()).toJSONObject();
    }
}
