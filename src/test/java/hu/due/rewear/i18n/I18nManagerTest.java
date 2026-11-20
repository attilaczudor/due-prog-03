package hu.due.rewear.i18n;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
