package indi.ohtoai.tool.client_tools.client.sequence;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Thin utility for managing .mcfunction sequence files.
 * Files are stored under {@code config/client-tools/sequences/}.
 */
public class CsequenceState {

    private static final Path SEQUENCES_DIR = FabricLoader.getInstance().getConfigDir()
        .resolve("client-tools").resolve("sequences");

    private CsequenceState() {}

    /**
     * Returns the sequences directory, creating it if necessary.
     */
    public static Path getSequencesDir() {
        try {
            Files.createDirectories(SEQUENCES_DIR);
        } catch (IOException ignored) {
            // Silently fail — will be reported on actual file operations
        }
        return SEQUENCES_DIR;
    }

    /**
     * Returns the .mcfunction file path for a given sequence name.
     * The name is sanitized and the .mcfunction extension is appended if missing.
     */
    public static Path getSequenceFile(String name) {
        String sanitized = sanitizeName(name);
        if (!sanitized.endsWith(".mcfunction")) {
            sanitized += ".mcfunction";
        }
        return getSequencesDir().resolve(sanitized);
    }

    /**
     * Lists all saved sequence names (without .mcfunction extension),
     * sorted alphabetically.
     */
    public static List<String> listSequences() {
        Path dir = getSequencesDir();
        if (!Files.exists(dir)) return List.of();

        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".mcfunction"))
                .forEach(p -> {
                    String name = p.getFileName().toString();
                    names.add(name.substring(0, name.length() - ".mcfunction".length()));
                });
        } catch (IOException ignored) {
            return List.of();
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }

    /**
     * Deletes a sequence file by name.
     *
     * @return true if the file was deleted, false if it didn't exist
     */
    public static boolean deleteSequence(String name) {
        Path file = getSequenceFile(name);
        try {
            return Files.deleteIfExists(file);
        } catch (IOException ignored) {
            return false;
        }
    }

    /**
     * Returns the size of a sequence file in bytes, or -1 if it doesn't exist.
     */
    public static long getFileSize(String name) {
        Path file = getSequenceFile(name);
        try {
            return Files.size(file);
        } catch (IOException ignored) {
            return -1;
        }
    }

    /**
     * Returns the number of command lines in a sequence file (excluding comments and blanks),
     * or -1 if the file doesn't exist.
     */
    public static int getCommandCount(String name) {
        Path file = getSequenceFile(name);
        if (!Files.exists(file)) return -1;

        try {
            int count = 0;
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    count++;
                }
            }
            return count;
        } catch (IOException ignored) {
            return -1;
        }
    }

    /**
     * Creates an empty .mcfunction file for the given name.
     *
     * @return the file path, or null if creation failed
     */
    public static Path createEmpty(String name) {
        Path file = getSequenceFile(name);
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                Files.createFile(file);
            }
            return file;
        } catch (IOException ignored) {
            return null;
        }
    }

    /**
     * Sanitize a name for use as a filename.
     * Replaces characters that are invalid on most filesystems with underscores.
     */
    private static String sanitizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }
}
