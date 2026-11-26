package hu.due.rewear.i18n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central CSV-backed internationalization manager.
 * Supports runtime language switching, dynamic discovery of new language columns,
 * fallback to English, and loading/exporting external CSV translation overrides.
 */
public final class I18nManager {

    private static final Logger LOG = LoggerFactory.getLogger(I18nManager.class);
    public static final String DEFAULT_RESOURCE = "/i18n/translations.csv";
    public static final String FALLBACK_LANGUAGE = "en";

    private static volatile I18nManager instance;

    private final CsvTranslationLoader loader = new CsvTranslationLoader();
    private final List<LanguageChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final java.util.Set<String> missingKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private CsvTranslationLoader.TranslationBundle bundle;
    private CsvTranslationLoader.TranslationBundle bundled;
    private String activeLanguage = FALLBACK_LANGUAGE;
    private ExternalSource loadedSource;

    public enum ExternalSourceStatus { CURRENT, RELOAD_REQUIRED, UNAVAILABLE }

    private record ExternalSource(Path path, FileTime modified, long size, Object fileKey) {
        static ExternalSource read(Path path) throws IOException {
            var attrs = Files.readAttributes(path, BasicFileAttributes.class);
            if (!attrs.isRegularFile() || !Files.isReadable(path)) {
                throw new IOException("Translation source is not a readable file: " + path);
            }
            return new ExternalSource(path, attrs.lastModifiedTime(), attrs.size(), attrs.fileKey());
        }
    }

    public I18nManager() {
        loadBundledDefault();
    }

    public I18nManager(CsvTranslationLoader.TranslationBundle initialBundle) {
        this.bundle = Objects.requireNonNull(initialBundle, "initialBundle");
        this.activeLanguage = initialBundle.languages().contains(FALLBACK_LANGUAGE)
                ? FALLBACK_LANGUAGE
                : initialBundle.languages().get(0);
    }

    public static I18nManager getInstance() {
        if (instance == null) {
            synchronized (I18nManager.class) {
                if (instance == null) {
                    instance = new I18nManager();
                }
            }
        }
        return instance;
    }

    public synchronized void loadBundledDefault() {
        try (InputStream is = I18nManager.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException("Bundled translation resource not found: " + DEFAULT_RESOURCE);
            }
            this.bundled = loader.load(is);
            this.bundle = bundled;
            this.loadedSource = null;
            if (!bundle.languages().contains(activeLanguage)) {
                this.activeLanguage = bundle.languages().contains(FALLBACK_LANGUAGE)
                        ? FALLBACK_LANGUAGE
                        : bundle.languages().get(0);
            }
            LOG.info("Loaded {} translation keys for languages {}", bundle.entries().size(), bundle.languages());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load bundled translations CSV", e);
        }
    }

    public synchronized void loadFromFile(Path csvPath) throws IOException {
        Objects.requireNonNull(csvPath, "csvPath");
        Path path = csvPath.toAbsolutePath().normalize();
        ExternalSource source = ExternalSource.read(path);
        CsvTranslationLoader.TranslationBundle loaded = loader.load(path);
        this.bundle = loaded;
        this.loadedSource = source;
        if (!bundle.languages().contains(activeLanguage)) {
            this.activeLanguage = bundle.languages().contains(FALLBACK_LANGUAGE)
                    ? FALLBACK_LANGUAGE
                    : bundle.languages().get(0);
        }
        LOG.info("Loaded external translations from {} ({} keys, languages={})",
                csvPath, bundle.entries().size(), bundle.languages());
        notifyListeners();
    }

    public synchronized ExternalSourceStatus externalSourceStatus(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) return ExternalSourceStatus.CURRENT;
        try {
            ExternalSource current = ExternalSource.read(Path.of(configuredPath).toAbsolutePath().normalize());
            return current.equals(loadedSource) ? ExternalSourceStatus.CURRENT : ExternalSourceStatus.RELOAD_REQUIRED;
        } catch (IOException | InvalidPathException ex) {
            return ExternalSourceStatus.UNAVAILABLE;
        }
    }

    public synchronized void exportToFile(Path targetPath) throws IOException {
        Objects.requireNonNull(targetPath, "targetPath");
        if (targetPath.getParent() != null) {
            Files.createDirectories(targetPath.getParent());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(targetPath, StandardCharsets.UTF_8)) {
            List<String> langs = bundle.languages();
            List<String> header = new ArrayList<>();
            header.add("key");
            header.addAll(langs);
            writer.write(String.join(",", header));
            writer.newLine();

            for (Map.Entry<String, Map<String, String>> entry : bundle.entries().entrySet()) {
                List<String> cols = new ArrayList<>();
                cols.add(CsvTranslationLoader.escapeCsvField(entry.getKey()));
                for (String lang : langs) {
                    String val = entry.getValue().getOrDefault(lang, "");
                    cols.add(CsvTranslationLoader.escapeCsvField(val));
                }
                writer.write(String.join(",", cols));
                writer.newLine();
            }
        }
    }

    public List<String> getAvailableLanguages() {
        return bundle.languages();
    }

    public String getActiveLanguage() {
        return activeLanguage;
    }

    public synchronized void setLanguage(String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            return;
        }
        String normalized = languageCode.trim().toLowerCase(Locale.ROOT);
        if (!bundle.languages().contains(normalized)) {
            throw new IllegalArgumentException("Unsupported language code: " + languageCode
                    + " (available: " + bundle.languages() + ")");
        }
        if (!normalized.equals(this.activeLanguage)) {
            this.activeLanguage = normalized;
            LOG.info("Switched active language to '{}'", normalized);
            notifyListeners();
        }
    }

    /**
     * Keys that were asked for and could not be answered.
     *
     * <p>A missing key does not throw: it renders as {@code [the.key]} in whatever header or button asked
     * for it, so nothing fails and nobody finds out until somebody looks at that screen. Recording them lets
     * the screen walkthrough fail the build instead of a reader finding it in a demo.
     */
    public java.util.Set<String> missingKeys() {
        return java.util.Set.copyOf(missingKeys);
    }

    /** Forgets what has been asked for so far, so a test can measure only its own screens. */
    public void forgetMissingKeys() {
        missingKeys.clear();
    }

    public String t(String key, Object... args) {
        return tForLanguage(activeLanguage, key, args);
    }

    public String tForLanguage(String languageCode, String key, Object... args) {
        if (key == null || key.isBlank()) {
            return "";
        }
        Map<String, String> perLang = bundle.entries().get(key);
        if (perLang == null && bundled != null) perLang = bundled.entries().get(key);
        if (perLang == null) {
            LOG.warn("Missing translation key: {}", key);
            missingKeys.add(key);
            missingKeys.add(key);
            return "[" + key + "]";
        }
        String normalizedLang = languageCode == null ? FALLBACK_LANGUAGE : languageCode.trim().toLowerCase(Locale.ROOT);
        String template = perLang.get(normalizedLang);
        if (template == null || template.isEmpty()) {
            template = perLang.get(FALLBACK_LANGUAGE);
        }
        if (template == null || template.isEmpty()) {
            template = perLang.values().stream()
                    .filter(v -> v != null && !v.isEmpty())
                    .findFirst()
                    .orElse(null);
        }
        if (template == null) {
            LOG.warn("Empty translation for key: {}", key);
            return "[" + key + "]";
        }
        if (args == null || args.length == 0) {
            return template;
        }
        return MessageFormat.format(template.replace("'", "''"), args);
    }

    public void addListener(LanguageChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(LanguageChangeListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        String lang = this.activeLanguage;
        for (LanguageChangeListener listener : listeners) {
            listener.onLanguageChanged(lang);
        }
    }
}
