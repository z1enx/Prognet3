import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Mencatat aktivitas server ke file (character stream: FileWriter + BufferedWriter).
 * Dipanggil dari banyak thread ClientHandler sekaligus, jadi method log() di-synchronized
 * supaya baris log dari thread berbeda tidak saling bertumpuk.
 */
public class ServerLogger implements Closeable {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BufferedWriter writer;

    public ServerLogger(String path) throws IOException {
        this.writer = new BufferedWriter(new FileWriter(path, StandardCharsets.UTF_8, true));
    }

    public synchronized void log(String message) {
        String line = "[" + LocalDateTime.now().format(FMT) + "] "
                + "[" + Thread.currentThread().getName() + "] " + message;
        System.out.println(line);
        try {
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            System.err.println("Gagal menulis log: " + e.getMessage());
        }
    }

    @Override
    public synchronized void close() throws IOException {
        writer.close();
    }
}
