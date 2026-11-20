package hu.due.rewear.dao;

/**
 * Unchecked exception wrapping JDBC and database persistence errors.
 */
public class DaoException extends RuntimeException {
    public DaoException(String message, Throwable cause) {
        super(message, cause);
    }

    public DaoException(String message) {
        super(message);
    }
}
