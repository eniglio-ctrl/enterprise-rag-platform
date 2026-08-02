package com.eniglio.ragplatform.auth.security;

import com.eniglio.ragplatform.auth.config.AuthProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A throwaway RSA key generated purely for this test fixture (not used anywhere real)
 * - verifying {@link JwtKeyProvider} parses a real PKCS8 PEM key and derives a stable
 * {@code kid} from it doesn't need a live secret, only a syntactically valid one.
 */
class JwtKeyProviderTest {

    private static final String TEST_PEM = """
            -----BEGIN PRIVATE KEY-----
            MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQDVkkZLEK7jObBJ
            fRuu6ngYLF9FTDR0TzCBL0lium3A6HhFq8dfatfRFPw2vuRMqDj8Uc8P1v8yWywa
            io19fyOlI6SUoeNxbD1mQ4y9MWr5lLa7hOAXnBGloZ3tJmzETk8CbgaZhyYklW1h
            jt0mgB3xs84zbFlz8LylVPfie2cyIf6s/NGKrzcl4/B78HtZkZdHYI2UCnag4hfK
            oGmJM9029qgyZg6iO2uW2GaA020hHSqeh2dtSEvhjwX8J/Gay1D5tEx9yparcfKM
            jIZ7SU8eEl4Ma10ovU4PEBxSEl4eAc/V8/5C4vtexBMoMTTnQAU6ktoe0GvnRD/z
            5n5IbQmXAgMBAAECggEADtOPyQhIdoJ7vYOk7K4Jq6FGSo5GxJ2harvN/BT10kLm
            jpelSKInMgmjP2hd0FXhVuBAYAN3ReypvCdK4/UelAlMafvEdAxP8DiCSGQU3sIM
            iX9o+y/yKFh27o67VZ7GF2TB/93aWlx78nOI4MewzHsaC9yFZ+oaKTQj3UzfK3ir
            jB1K8NmS1g0pXMaXQw/p6U3R8zcX44YkpRzdh1zgIdqVxMDu0hojhodHhA9N5NSO
            rXUXtyweK1OzeRBvPPYtq4wNMy32A75tyjajuUvY/AQ1hO4nSC8+UcM/F8+o2N3S
            eBNE+UHIhw5Sr20WjwIEChoD3oM0M7hxOrN7t7I1uQKBgQDvqrNR76z+pO7gUAHR
            fY/ssYRoD78HKPl1jwswJrpP2ldB/1wNwDI36Y+K405GffccYpNuwW8zQhZ4EcL3
            rdLIj4ffj74805mU1iFMHU97U+vKFiZ31oxmkOdXulAhis0g237sz12S3S6PWOak
            ryLeR7Rkx5wCFOa1/JqH0vHvawKBgQDkIE49mGQzYr3XU2qLYboAWBfLgUXD6RSR
            xPXWZYv67jjmgi/u5N7Qy6SI04KHF3KbimwOVpfEy9rT+3eySwvHSAjcFVoG9Vxg
            wJ3fYrUu+FEkgZtUfMGYyuBz/sfFJSCAgcI6N0DPoq2OSifPNwzLVoj4akG1aZiM
            SJ61jZ+1hQKBgFlps9DDvCScX8orzyHa2FETwTQZe8kuDjM/lIr4R9X2vUsP+8Xd
            iF63sie8ub0uzXw3go11eQkEhOFyruw8W0Eb2zMaq3yB7PBMGswMu1RlcLhKHzvi
            PSsesFBYYADDidfSS74Jdv2NqwsrvZB/DmEjGzfmCFv2dEQ842H83unVAoGAduOr
            Qwint+wO+ihcD5X35PQEOqf9nvNbJ/kZEgpMIJOEjgVtS7h1syVec3yfux3qOcuz
            MunIIOUI/48/u2jHE62kCGcMSzIWWcoovOHpgTgiub7eH/MAxkt9HJa8sC8AOFjn
            y4U+PrgOcWiORLzw6wwHSEfARPZqbTnvlLjhci0CgYB8lmf/ODPRGe82KU9suW69
            nEg17AwnZJJfW3pHlRiGpCJ619wa7GzctdFNUrrrRW6KK/nqrFCcgHyOe6H1N3f8
            feG+tLEJBQoHxrJBE5Vcm2BVofWFgwTvUzzbnyHdn2+TfNO5ceAZK8uXackVNHJP
            QKwiiBw2wgRYQuqnOHFr2w==
            -----END PRIVATE KEY-----
            """;

    private static AuthProperties.SigningKey blank() {
        return new AuthProperties.SigningKey("", "");
    }

    @Test
    void fallsBackToAnEphemeralKeyWhenNothingIsConfigured() {
        JwtKeyProvider provider = new JwtKeyProvider(new AuthProperties(Duration.ofHours(1), Duration.ofDays(7),
                blank()));

        assertThat(provider.signingKey()).isNotNull();
        assertThat(provider.signingKey().isPrivate()).isTrue();
        assertThat(provider.publicJwk().isPrivate()).isFalse();
    }

    @Test
    void loadsTheConfiguredKeyFromABase64EncodedValue() {
        String base64 = Base64.getEncoder().encodeToString(TEST_PEM.getBytes(StandardCharsets.UTF_8));
        AuthProperties.SigningKey signingKey = new AuthProperties.SigningKey("", base64);
        JwtKeyProvider provider = new JwtKeyProvider(new AuthProperties(Duration.ofHours(1), Duration.ofDays(7),
                signingKey));

        assertThat(provider.signingKey().isPrivate()).isTrue();
        assertThat(provider.signingKey().getKeyID()).isNotBlank();
    }

    @Test
    void loadsTheConfiguredKeyFromAFilePath(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        Path keyFile = tempDir.resolve("signing-key.pem");
        Files.writeString(keyFile, TEST_PEM, StandardCharsets.UTF_8);
        AuthProperties.SigningKey signingKey = new AuthProperties.SigningKey(keyFile.toString(), "");
        JwtKeyProvider provider = new JwtKeyProvider(new AuthProperties(Duration.ofHours(1), Duration.ofDays(7),
                signingKey));

        assertThat(provider.signingKey().isPrivate()).isTrue();
    }

    @Test
    void theKeyIdIsStableAcrossRestartsBecauseItsDerivedFromTheKeyItselfNotRandom() {
        String base64 = Base64.getEncoder().encodeToString(TEST_PEM.getBytes(StandardCharsets.UTF_8));
        AuthProperties.SigningKey signingKey = new AuthProperties.SigningKey("", base64);
        AuthProperties properties = new AuthProperties(Duration.ofHours(1), Duration.ofDays(7), signingKey);

        // Two independent providers loading the same persisted key - simulating two
        // restarts - must agree on the kid, or a token signed before a restart would
        // fail JWKS lookup by kid after one.
        JwtKeyProvider first = new JwtKeyProvider(properties);
        JwtKeyProvider second = new JwtKeyProvider(properties);

        assertThat(first.signingKey().getKeyID()).isEqualTo(second.signingKey().getKeyID());
    }
}
