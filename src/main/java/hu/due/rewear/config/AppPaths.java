package hu.due.rewear.config;

import hu.due.rewear.App;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves portable relative storage paths located next to the running application JAR
 * (or project root when executed from an IDE / Maven target directory), ensuring that
 * double-clicking the JAR on macOS/Windows/Linux always accesses {@code ./data/} next to the app.
 */
public final class AppPaths {

    private AppPaths() {
    }

    public static Path resolveAppBaseDir() {
        try {
            URI uri = App.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path codePath = Path.of(uri).toAbsolutePath().normalize();
            Path dir = Files.isDirectory(codePath) ? codePath : codePath.getParent();

            if (dir != null) {
                // If running from Maven target/classes or target/*.jar during development, anchor to project root
                if ("classes".equals(dir.getFileName().toString())
                        && dir.getParent() != null
                        && "target".equals(dir.getParent().getFileName().toString())) {
                    Path projectRoot = dir.getParent().getParent();
                    if (projectRoot != null && Files.exists(projectRoot.resolve("pom.xml"))) {
                        return projectRoot;
                    }
                } else if ("target".equals(dir.getFileName().toString())
                        && dir.getParent() != null
                        && Files.exists(dir.getParent().resolve("pom.xml"))) {
                    return dir.getParent();
                }
                return dir;
            }
        } catch (Exception ignored) {
            // Fall back to current working directory
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    public static Path resolveDataDir() {
        return resolveAppBaseDir().resolve("data");
    }

    public static Path defaultDatabasePath() {
        return resolveDataDir().resolve("rewear-wms.db");
    }

    public static Path defaultPreferencesPath() {
        return resolveDataDir().resolve("app.properties");
    }
}
