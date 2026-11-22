package hu.due.rewear.i18n;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class I18nManagerTest {

    @Test
    void loadsBundledEnglishAndHungarianTranslations() {
        I18nManager manager = new I18nManager();

        assertEquals(List.of("en", "hu"), manager.getAvailableLanguages());
        assertEquals("en", manager.getActiveLanguage());
        assertEquals("Sign In", manager.t("login.button"));

        manager.setLanguage("hu");
        assertEquals("Bejelentkezés", manager.t("login.button"));
        assertEquals("5 csomag sikeresen elmentve.", manager.t("intake.saved_success", 5));
    }

    @Test
    void discoversAdditionalLanguageColumnDynamically(@TempDir Path tempDir) throws IOException {
        Path csv = tempDir.resolve("custom_translations.csv");
        Files.writeString(csv, """
                key,en,hu,de
                greeting,"Hello, {0}!","Szia, {0}!","Hallo, {0}!"
                only.en,English Only,,
                """, StandardCharsets.UTF_8);

        I18nManager manager = new I18nManager();
        AtomicReference<String> notifiedLang = new AtomicReference<>();
        manager.addListener(notifiedLang::set);

        manager.loadFromFile(csv);
        assertEquals(List.of("en", "hu", "de"), manager.getAvailableLanguages());

        manager.setLanguage("de");
        assertEquals("de", notifiedLang.get());
        assertEquals("Hallo, Attila!", manager.t("greeting", "Attila"));
        // Falls back to English when German translation is blank
        assertEquals("English Only", manager.t("only.en"));
        // Missing key returns bracketed key
        assertEquals("[nonexistent.key]", manager.t("nonexistent.key"));
    }

    @Test
    void exportsAndReloadsCsvLosslessly(@TempDir Path tempDir) throws IOException {
        I18nManager manager = new I18nManager();
        Path exported = tempDir.resolve("exported.csv");

        manager.exportToFile(exported);
        assertTrue(Files.exists(exported));

        I18nManager reloaded = new I18nManager();
        reloaded.loadFromFile(exported);
        manager.setLanguage("hu");
        reloaded.setLanguage("hu");
        assertEquals(manager.t("login.demo_hint"), reloaded.t("login.demo_hint"));
    }

    @Test
    void rejectsUnsupportedLanguageCode() {
        I18nManager manager = new I18nManager();
        assertThrows(IllegalArgumentException.class, () -> manager.setLanguage("fr"));
    }

    @Test
    void bundledTranslationsCsvHasCleanStructureAndCompleteBilingualEntries() throws IOException {
        try (InputStream is = I18nManager.class.getResourceAsStream(I18nManager.DEFAULT_RESOURCE)) {
            assertNotNull(is, "Bundled translations.csv must exist");
            List<List<String>> rows = CsvTranslationLoader.parseCsvRows(
                    new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)));
            assertTrue(rows.size() > 100, "Expected populated translations.csv");
            assertEquals(List.of("key", "en", "hu"), rows.get(0), "Header must be key,en,hu");

            Set<String> seenKeys = new HashSet<>();
            for (int i = 1; i < rows.size(); i++) {
                List<String> row = rows.get(i);
                assertEquals(3, row.size(), "Row " + (i + 1) + " has malformed column count (check unquoted commas): " + row);
                String key = row.get(0).trim();
                String en = row.get(1);
                String hu = row.get(2);
                assertFalse(key.isEmpty(), "Blank key at row " + (i + 1));
                assertTrue(seenKeys.add(key), "Duplicate translation key: " + key);
                assertFalse(en.isBlank(), "Missing English translation for key: " + key);
                assertFalse(hu.isBlank(), "Missing Hungarian translation for key: " + key);
                assertFalse(en.contains("''"), "Double apostrophe in EN for key (I18nManager escapes single quotes automatically): " + key);
                assertFalse(hu.contains("''"), "Double apostrophe in HU for key (I18nManager escapes single quotes automatically): " + key);
            }
        }

        // Verify previously comma-broken keys now resolve properly in Hungarian
        I18nManager hu = new I18nManager();
        hu.setLanguage("hu");
        assertTrue(hu.t("dictionaries.code_hint").contains("kötőjel"), "HU code_hint must load cleanly");
        assertTrue(hu.t("docpreview.none_selected").contains("dokumentumot"), "HU docpreview.none_selected must load cleanly");
        assertFalse(hu.t("dictionaries.delete_confirm", "TSH").contains("''"), "Delete confirm must render single quotes, not doubled");
    }
}
