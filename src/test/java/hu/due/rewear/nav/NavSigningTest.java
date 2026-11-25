package hu.due.rewear.nav;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cryptography NAV checks before it reads anything else.
 *
 * <p>The expected digests here were produced independently, outside this codebase, from the same inputs.
 * That matters: the live service cannot be used to check an implementation, so the only honest verification
 * is against a second implementation of the published algorithm. A test that hashed the value with the class
 * under test and compared it to itself would pass no matter what the class did.
 */
class NavSigningTest {

    private static final Instant AT = Instant.parse("2026-11-25T10:20:30Z");
    private static final String REQUEST_ID = "RWTESTREQUEST000000000001";
    private static final String SIGN_KEY = "sign-key-abcdefghijklmno";

    @Test
    void theTimestampIsTheSameInstantInTwoFormats() {
        assertEquals("20261125102030", NavRequestSigner.signatureTimestamp(AT));
        assertEquals("2026-11-25T10:20:30.000Z", NavRequestSigner.headerTimestamp(AT));
    }

    @Test
    void thePasswordIsHashedWithSha512() {
        assertEquals(
                "CD8B2645D93F5F41CC92D98775ABD7B9E7276DDF24D44B6380056B7EB2958C857BB90CF148CBE878A328C524"
                        + "77C1A77B56373D443BCCD7F9AD2FF35D4EA6B07C",
                NavRequestSigner.passwordHash("Technical-Pass-2026"));
    }

    @Test
    void aRequestWithoutInvoicesIsSignedOverTheIdTheTimeAndTheKey() {
        assertEquals(
                "0C94A4B3B0439E4A5790E9A2278B1B42FC40FDF76F6DC141C418CD71AA43C6C96339A5E317E40773DB04EDA5"
                        + "2B544C8DE153F9F24DBF3CDA6F03DBA9F4494CEC",
                NavRequestSigner.requestSignature(REQUEST_ID, AT, SIGN_KEY, List.of()));
    }

    @Test
    void eachInvoiceAddsItsOwnDigestToTheSignature() {
        String payload = Base64.getEncoder().encodeToString("<InvoiceData/>".getBytes(StandardCharsets.UTF_8));
        assertEquals("PEludm9pY2VEYXRhLz4=", payload, "Precondition: the payload is what was hashed outside");
        assertEquals(
                "B85C29A0AE1CF28868F049E6FD6C727C380B942578D9A7A3ECEE5FBCD1430898485B1C6035DA1B65C7B70482"
                        + "90E5FB3D811CA65110879A690CF0C7C6F9B9C9C8",
                NavRequestSigner.requestSignature(REQUEST_ID, AT, SIGN_KEY, List.of("CREATE" + payload)));
    }

    @Test
    void theSignatureIsSha3NotSha2() {
        // Were SHA-512 used where SHA3-512 is required, every request would be rejected. The two digests of
        // the same string differ, so this fails loudly if the algorithm is ever swapped.
        String sha3 = NavRequestSigner.requestSignature(REQUEST_ID, AT, SIGN_KEY, List.of());
        String sha2 = NavRequestSigner.passwordHash(REQUEST_ID + "20261125102030" + SIGN_KEY);
        org.junit.jupiter.api.Assertions.assertNotEquals(sha2, sha3);
    }

    @Test
    void anExchangeTokenIsDecryptedWithTheExchangeKey() throws Exception {
        String key = "0123456789abcdef";
        String token = "abcdef0123456789-token";
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"));
        String encoded = Base64.getEncoder()
                .encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)));

        assertEquals(token, NavRequestSigner.decodeExchangeToken(encoded, key));
    }

    @Test
    void aWrongExchangeKeyIsReportedAsSuchAndNotRetried() {
        NavException thrown = assertThrows(NavException.class,
                () -> NavRequestSigner.decodeExchangeToken("bm90LWEtdG9rZW4=", "0123456789abcdef"));
        assertTrue(thrown.getMessage().toLowerCase().contains("exchange key"));
        // Retrying a wrong key just produces the same failure; somebody has to fix the key.
        org.junit.jupiter.api.Assertions.assertFalse(thrown.retryable());
    }

    @Test
    void aRequestIdFitsWhatTheInterfaceAccepts() {
        for (int i = 0; i < 50; i++) {
            String id = NavRequestSigner.newRequestId();
            assertTrue(id.matches("[+a-zA-Z0-9_]{1,30}"), "Unacceptable request id: " + id);
        }
    }
}
