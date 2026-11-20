package hu.due.rewear.i18n;

/**
 * Listener notified whenever the active UI language changes or translations are reloaded.
 */
@FunctionalInterface
public interface LanguageChangeListener {
    void onLanguageChanged(String languageCode);
}
