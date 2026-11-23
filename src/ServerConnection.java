import javax.swing.SwingUtilities;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Pengganti DBCon di sisi client. Semua data sekarang diminta ke server lewat socket.
 *
 * Thread di sisi client:
 *  - listener thread : terus membaca socket. Baris "PUSH_..." diteruskan ke listener UI,
 *                      baris lain dimasukkan ke antrean respons.
 *  - request thread  : mengirim request dan menunggu respons, supaya Event Dispatch Thread
 *                      (thread UI Swing) tidak pernah macet menunggu jaringan.
 */
public class ServerConnection {

    private static final String DISCONNECTED = "__DISCONNECTED__";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int RESPONSE_TIMEOUT_SEC = 10;

    private final Socket socket;
    private final BufferedReader in;
    private final PrintWriter out;

    private final BlockingQueue<String> responses = new LinkedBlockingQueue<>();
    private final Map<String, List<Consumer<String[]>>> pushListeners = new ConcurrentHashMap<>();
    private final ExecutorService requestExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "request-sender");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean connected = true;
    private volatile Runnable onDisconnect;
    private volatile Consumer<String> defaultErrorHandler = msg -> System.err.println(msg);

    public ServerConnection(String host, int port) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)), true);

        Thread listener = new Thread(this::listen, "server-listener");
        listener.setDaemon(true);
        listener.start();
    }

    private void listen() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (Protocol.isPush(line)) {
                    dispatchPush(Protocol.parse(line));
                } else {
                    responses.put(line);
                }
            }
        } catch (IOException | InterruptedException e) {
            // koneksi ditutup
        } finally {
            connected = false;
            responses.offer(DISCONNECTED);
            Runnable cb = onDisconnect;
            if (cb != null) SwingUtilities.invokeLater(cb);
        }
    }

    private void dispatchPush(String[] parts) {
        List<Consumer<String[]>> list = pushListeners.get(parts[0]);
        if (list == null) return;
        for (Consumer<String[]> l : list) {
            SwingUtilities.invokeLater(() -> l.accept(parts));
        }
    }

    /**
     * Kirim request dan tunggu respons (blocking). Jangan dipanggil dari thread UI;
     * pakai requestAsync().
     */
    public synchronized String[] request(String... parts) throws IOException {
        if (!connected) throw new IOException("Tidak terhubung ke server");
        responses.clear();
        out.println(Protocol.build(parts));
        if (out.checkError()) throw new IOException("Gagal mengirim data ke server");
        try {
            String line = responses.poll(RESPONSE_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (line == null) throw new IOException("Server tidak merespons");
            if (DISCONNECTED.equals(line)) throw new IOException("Koneksi ke server terputus");
            return Protocol.parse(line);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request dibatalkan");
        }
    }

    /** Kirim request di background, hasilnya dikembalikan di thread UI. */
    public void requestAsync(Consumer<String[]> onResult, Consumer<String> onError, String... parts) {
        requestExecutor.execute(() -> {
            try {
                String[] r = request(parts);
                SwingUtilities.invokeLater(() -> onResult.accept(r));
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> onError.accept(e.getMessage()));
            }
        });
    }

    public void requestAsync(Consumer<String[]> onResult, String... parts) {
        requestAsync(onResult, msg -> defaultErrorHandler.accept(msg), parts);
    }

    public void onPush(String type, Consumer<String[]> listener) {
        pushListeners.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public void setOnDisconnect(Runnable r) { this.onDisconnect = r; }

    public void setDefaultErrorHandler(Consumer<String> h) { this.defaultErrorHandler = h; }

    public void close() {
        connected = false;
        requestExecutor.shutdownNow();
        try { socket.close(); } catch (IOException ignored) {}
    }
}
