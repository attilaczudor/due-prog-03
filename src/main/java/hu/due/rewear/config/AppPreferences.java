package hu.due.rewear.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Persists lightweight UI preferences (language, dark/light theme, external CSV translation path).
 */
public final class AppPreferences {

    private static final Logger LOG = LoggerFactory.getLogger(AppPreferences.class);
    public static final Path DEFAULT_PREFS_PATH = AppPaths.defaultPreferencesPath();

    private static final String KEY_LANGUAGE = "ui.language";
    private static final String KEY_DARK_THEME = "ui.darkTheme";
    private static final String KEY_EXTERNAL_I18N = "i18n.externalCsv";

    private final Path filePath;
    private final Properties props = new Properties();

    public AppPreferences() {
        this(DEFAULT_PREFS_PATH);
    }

    public AppPreferences(Path filePath) {
        this.filePath = filePath.toAbsolutePath();
        load();
    }

    private void load() {
        if (!Files.exists(filePath)) {
            return;
        }
        try (InputStream is = Files.newInputStream(filePath)) {
            props.load(is);
        } catch (IOException e) {
            LOG.warn("Could not read preferences from {}: {}", filePath, e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            if (filePath.getParent() != null) {
                Files.createDirectories(filePath.getParent());
            }
            try (OutputStream os = Files.newOutputStream(filePath)) {
                props.store(os, "ReWear WMS User Preferences");
            }
        } catch (IOException e) {
            LOG.warn("Could not save preferences to {}: {}", filePath, e.getMessage());
        }
    }

    public String getLanguage() {
        return props.getProperty(KEY_LANGUAGE, "en");
    }

    public void setLanguage(String languageCode) {
        if (languageCode != null && !languageCode.isBlank()) {
            props.setProperty(KEY_LANGUAGE, languageCode.trim().toLowerCase());
            save();
        }
    }

    public boolean isDarkTheme() {
        return Boolean.parseBoolean(props.getProperty(KEY_DARK_THEME, "false"));
    }

    public void setDarkTheme(boolean darkTheme) {
        props.setProperty(KEY_DARK_THEME, Boolean.toString(darkTheme));
        save();
    }

    public String getExternalTranslationsPath() {
        return props.getProperty(KEY_EXTERNAL_I18N, "");
    }

    public void setExternalTranslationsPath(String path) {
        if (path == null || path.isBlank()) {
            props.remove(KEY_EXTERNAL_I18N);
        } else {
            props.setProperty(KEY_EXTERNAL_I18N, path.trim());
        }
        save();
    }
}
