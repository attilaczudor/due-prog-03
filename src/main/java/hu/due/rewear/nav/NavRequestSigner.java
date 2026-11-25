package hu.due.rewear.nav;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * The cryptography the Online Számla interface requires: the password hash, the request signature, and the
 * exchange token that comes back encrypted.
 *
 * <p>Three separate things are at work and the specification uses a different algorithm for each, which is
 * exactly the sort of detail that is easy to get wrong once and never notice:
 * <ul>
 *   <li>the technical user's password is hashed with <b>SHA-512</b>;</li>
 *   <li>the request signature is <b>SHA3-512</b> — the Keccak family, not SHA-2 — over the request id, the
 *       timestamp to the second, the signing key, and one digest per invoice in the request;</li>
 *   <li>the exchange token arrives encrypted with <b>AES-128 in ECB mode</b> under the exchange key.</li>
 * </ul>
 * Every digest is written as uppercase hexadecimal, which the service checks literally.
 *
 * <p>This class is deliberately free of input and output. Everything here can be checked against a digest
 * computed by any other tool, which is how the tests verify it, since the live service cannot be used for that.
 */
public final class NavRequestSigner {

    /** The signature covers the timestamp to the second, without punctuation, in UTC. */
    private static final DateTimeFormatter SIGNATURE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    /** The header carries the same instant in ISO-8601, with milliseconds, in UTC. */
    private static final DateTimeFormatter HEADER_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private NavRequestSigner() { }

    /** The request id must be unique for the taxpayer and may hold at most thirty of a restricted set of characters. */
    public static String newRequestId() {
        StringBuilder id = new StringBuilder("RW");
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int i = 0; i < 26; i++) {
            id.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return id.toString();
    }

    public static String headerTimestamp(Instant at) {
        return HEADER_TIMESTAMP.format(at);
    }

    public static String signatureTimestamp(Instant at) {
        return SIGNATURE_TIMESTAMP.format(at);
    }

    /** SHA-512 of the technical user's password, uppercase hex. */
    public static String passwordHash(String password) {
        return hex(digest("SHA-512", password.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * The request signature: SHA3-512 of the request id, the timestamp to the second, the signing key, and
     * then, for a request that carries invoices, the SHA3-512 of each operation and its payload in order.
     *
     * @param invoiceDigestInputs for each invoice in the request, the operation name immediately followed by
     *                            the Base64 payload exactly as it appears in the request
     */
    public static String requestSignature(String requestId, Instant at, String signKey,
                                          List<String> invoiceDigestInputs) {
        StringBuilder base = new StringBuilder()
                .append(requestId)
                .append(signatureTimestamp(at))
                .append(signKey);
        for (String input : invoiceDigestInputs) {
            base.append(hex(digest("SHA3-512", input.getBytes(StandardCharsets.UTF_8))));
        }
        return hex(digest("SHA3-512", base.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /** Decrypts the exchange token NAV returns. The exchange key is used as the AES key exactly as issued. */
    public static String decodeExchangeToken(String encodedToken, String exchangeKey) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(exchangeKey.getBytes(StandardCharsets.UTF_8), "AES"));
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(encodedToken));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (RuntimeException ex) {
            throw new NavException("The exchange token could not be read; check the exchange key", false, null, ex);
        } catch (Exception ex) {
            throw new NavException("The exchange token could not be decrypted; check the exchange key",
                    false, null, ex);
        }
    }

    private static byte[] digest(String algorithm, byte[] input) {
        try {
            return MessageDigest.getInstance(algorithm).digest(input);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(algorithm + " is required by the NAV interface", ex);
        }
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out).toUpperCase(Locale.ROOT);
    }
}
