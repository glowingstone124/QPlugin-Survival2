package vip.qoriginal.quantumplugin.hot.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class AtomicFiles {
    private AtomicFiles() { }
    public static void write(Path destination, String text) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), ".qhot-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
