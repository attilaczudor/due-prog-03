package hu.due.rewear.nav;

/**
 * The technical user's secrets: the password, the signing key and the exchange key.
 *
 * <p>Only a primary user can create a technical user and generate these keys, and only on NAV's own web
 * interface. The application therefore receives them, uses them, and can tell an administrator when they look
 * wrong — but it can never create or renew them, which makes rotation an operational procedure rather than a
 * button.
 */
public record NavCredentials(String password, String signKey, String exchangeKey) {

    public boolean isComplete() {
        return notBlank(password) && notBlank(signKey) && notBlank(exchangeKey);
    }

    /**
     * The exchange key is an AES-128 key, so sixteen characters and no other length will decrypt a token.
     * Checking it here turns a puzzling decryption failure at reporting time into a plain message at setup.
     */
    public boolean hasUsableExchangeKey() {
        return exchangeKey != null && exchangeKey.length() == 16;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public String toString() {
        // Never let a credential reach a log line through a careless string concatenation.
        return "NavCredentials[present=" + isComplete() + "]";
    }
}
