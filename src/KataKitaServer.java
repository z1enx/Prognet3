import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Server KataKita.
 *
 * Thread yang berjalan di server:
 *  - main thread        : menunggu koneksi baru di ServerSocket.accept()
 *  - clientPool         : satu thread per client (ClientHandler)
 *  - scheduler          : timer 2 menit tiap sesi game (waktu habis diputuskan server)
 *  - broadcaster        : mengirim leaderboard/info ke semua client tanpa menahan thread client
 *
 * Cara menjalankan: java -cp "out/server;mysql-connector-j.jar" KataKitaServer [port]
 */
public class KataKitaServer {

    private final int port;
    private final ExecutorService clientPool = Executors.newCachedThreadPool(named("client"));
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, named("timer"));
    private final ExecutorService broadcaster = Executors.newSingleThreadExecutor(named("broadcast"));

    /** Set yang aman diakses banyak thread. */
    private final Set<ClientHandler> clients = ConcurrentHashMap.newKeySet();

    private final ServerLogger logger;

    public KataKitaServer(int port, ServerLogger logger) {
        this.port = port;
        this.logger = logger;
    }

    public void start() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            logger.log("Server KataKita berjalan di port " + port);
            while (true) {
                Socket socket = serverSocket.accept();
                ClientHandler handler = new ClientHandler(socket, this);
                clients.add(handler);
                clientPool.execute(handler);
                logger.log("Client terhubung dari " + socket.getRemoteSocketAddress()
                        + " (total koneksi: " + clients.size() + ")");
            }
        }
    }

    // ---------------------------------------------------------------------
    // Dipanggil oleh ClientHandler
    // ---------------------------------------------------------------------

    public ServerLogger logger() { return logger; }

    public ScheduledExecutorService scheduler() { return scheduler; }

    public void removeClient(ClientHandler handler) {
        clients.remove(handler);
        broadcastOnlineCount();
    }

    /** Kirim pesan ke semua client yang sudah login. */
    private void broadcast(String message) {
        for (ClientHandler c : clients) {
            if (c.isLoggedIn()) c.send(message);
        }
    }

    public void broadcastOnlineCount() {
        broadcaster.execute(() -> {
            long online = clients.stream().filter(ClientHandler::isLoggedIn).count();
            broadcast(Protocol.build(Protocol.PUSH_ONLINE, String.valueOf(online)));
        });
    }

    public void broadcastInfo(String text) {
        broadcaster.execute(() -> broadcast(Protocol.build(Protocol.PUSH_INFO, text)));
    }

    /** Query leaderboard dijalankan di thread broadcaster, bukan di thread client yang memicu. */
    public void broadcastLeaderboard() {
        broadcaster.execute(() -> broadcast(Protocol.build(Protocol.PUSH_LB, leaderboardPayload())));
    }

    public String leaderboardPayload() {
        List<Object[]> rows = new DBCon().getLeaderboard();
        StringBuilder sb = new StringBuilder();
        for (Object[] r : rows) {
            if (sb.length() > 0) sb.append(Protocol.ROW_SEP);
            Timestamp ts = (Timestamp) r[5];
            sb.append(r[0]).append(Protocol.COL_SEP)
              .append(r[1]).append(Protocol.COL_SEP)
              .append(r[2]).append(Protocol.COL_SEP)
              .append(r[3]).append(Protocol.COL_SEP)
              .append(r[4]).append(Protocol.COL_SEP)
              .append(ts == null ? 0 : ts.getTime());
        }
        return sb.toString();
    }

    private static java.util.concurrent.ThreadFactory named(String prefix) {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : Protocol.DEFAULT_PORT;
        ServerLogger logger = new ServerLogger("server.log");
        new KataKitaServer(port, logger).start();
    }
}
