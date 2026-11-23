import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Melayani satu client di thread tersendiri.
 *
 * Stream yang dipakai:
 *   socket.getInputStream()  -> InputStreamReader -> BufferedReader  (baca per baris)
 *   socket.getOutputStream() -> OutputStreamWriter -> BufferedWriter -> PrintWriter
 *
 * Method send() bisa dipanggil dari tiga thread berbeda (thread handler ini,
 * thread broadcaster, thread timer), sehingga dibuat synchronized supaya dua pesan
 * tidak tercampur di tengah baris.
 */
public class ClientHandler implements Runnable {

    private final Socket socket;
    private final KataKitaServer server;
    private final DBCon db = new DBCon();

    private BufferedReader in;
    private PrintWriter out;

    private volatile int playerId = -1;
    private volatile String username;

    private volatile GameSession session;
    private volatile ScheduledFuture<?> timeoutTask;

    public ClientHandler(Socket socket, KataKitaServer server) {
        this.socket = socket;
        this.server = server;
    }

    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)), true);

            String line;
            while ((line = in.readLine()) != null) {
                String response;
                try {
                    response = handle(line);
                } catch (RuntimeException e) {
                    server.logger().log("Error memproses '" + line + "': " + e);
                    response = Protocol.build(Protocol.ERR, "Terjadi kesalahan di server");
                }
                if (response != null) send(response);
            }
        } catch (IOException e) {
            server.logger().log("Koneksi " + who() + " terputus: " + e.getMessage());
        } finally {
            server.race().leave(this);
            abandonSession();
            playerId = -1;
            server.removeClient(this);
            try { socket.close(); } catch (IOException ignored) {}
            server.logger().log("Client " + socket.getRemoteSocketAddress() + " keluar");
        }
    }

    public synchronized void send(String message) {
        if (out != null) out.println(message);
    }

    public boolean isLoggedIn() {
        return playerId > 0;
    }

    public String getUsername() {
        return username;
    }

    public int getPlayerId() {
        return playerId;
    }

    private String who() {
        return username != null ? username : String.valueOf(socket.getRemoteSocketAddress());
    }

    // ---------------------------------------------------------------------
    // Router perintah
    // ---------------------------------------------------------------------

    private String handle(String line) {
        String cmd = Protocol.parse(line)[0];

        switch (cmd) {
            case Protocol.LOGIN:       return handleLogin(line);
            case Protocol.REGISTER:    return handleRegister(line);
            case Protocol.LOGOUT:      return handleLogout();
            case Protocol.START:       return requireLogin() ? handleStart() : notLoggedIn();
            case Protocol.GUESS:       return requireLogin() ? handleGuess(line) : notLoggedIn();
            case Protocol.QUIT_GAME:   return requireLogin() ? handleQuit() : notLoggedIn();
            case Protocol.LEADERBOARD: return Protocol.build(Protocol.LB, server.leaderboardPayload());
            case Protocol.LAST_RESULT: return requireLogin() ? handleLastResult() : notLoggedIn();

            // Mode balapan
            case Protocol.RACE_JOIN:   return requireLogin() ? server.race().join(this) : notLoggedIn();
            case Protocol.RACE_START:  return requireLogin() ? server.race().start(this) : notLoggedIn();
            case Protocol.RACE_GUESS: {
                if (!requireLogin()) return notLoggedIn();
                String[] p = Protocol.parse(line);
                return server.race().guess(this, p.length > 1 ? p[1] : "");
            }
            case Protocol.RACE_LEAVE:
                server.race().leave(this);
                return Protocol.OK;
            default:
                return Protocol.build(Protocol.ERR, "Perintah tidak dikenal: " + cmd);
        }
    }

    private boolean requireLogin() { return isLoggedIn(); }

    private String notLoggedIn() { return Protocol.build(Protocol.ERR, "Silakan login dulu"); }

    // ---------------------------------------------------------------------
    // Akun
    // ---------------------------------------------------------------------

    private String handleLogin(String line) {
        // limit 3 supaya password yang mengandung "|" tetap utuh
        String[] p = line.split(Protocol.SEP_REGEX, 3);
        if (p.length < 3) return Protocol.build(Protocol.ERR, "Format login salah");

        int id = db.loginPlayer(p[1], p[2]);
        if (id <= 0) {
            server.logger().log("Login gagal untuk '" + p[1] + "'");
            return Protocol.build(Protocol.ERR, "Username atau password salah.");
        }
        playerId = id;
        username = p[1];
        server.logger().log("Login: " + username + " (id " + id + ")");
        server.broadcastOnlineCount();
        return Protocol.build(Protocol.OK, String.valueOf(id), username);
    }

    private String handleRegister(String line) {
        String[] p = line.split(Protocol.SEP_REGEX, 3);
        if (p.length < 3) return Protocol.build(Protocol.ERR, "Format registrasi salah");
        if (!p[1].matches(Protocol.USERNAME_REGEX)) {
            return Protocol.build(Protocol.ERR, "Username 3-20 karakter, hanya huruf, angka, dan _");
        }
        if (p[2].length() < 4) {
            return Protocol.build(Protocol.ERR, "Password minimal 4 karakter");
        }
        String error = db.registerPlayer(p[1], p[2]);
        if (error == null) {
            server.logger().log("Registrasi akun baru: " + p[1]);
            return Protocol.OK;
        }
        server.logger().log("Registrasi gagal untuk '" + p[1] + "': " + error);
        return Protocol.build(Protocol.ERR, error);
    }

    private String handleLogout() {
        server.race().leave(this);
        abandonSession();
        server.logger().log("Logout: " + who());
        playerId = -1;
        username = null;
        server.broadcastOnlineCount();
        return Protocol.OK;
    }

    // ---------------------------------------------------------------------
    // Permainan
    // ---------------------------------------------------------------------

    private long cooldownRemaining() {
        long last = db.getLastGameTime(playerId);
        if (last <= 0) return 0;
        return Math.max(0, Protocol.COOLDOWN_MS - (System.currentTimeMillis() - last));
    }

    private String handleStart() {
        abandonSession();

        long remaining = cooldownRemaining();
        if (remaining > 0) {
            return Protocol.build(Protocol.COOLDOWN, String.valueOf(remaining));
        }

        Map<String, Integer> words = db.getSoal();
        if (words.isEmpty()) words.put("HEBAT", 1);
        ArrayList<String> list = new ArrayList<>(words.keySet());
        String target = list.get(new Random().nextInt(list.size()));

        GameSession s = new GameSession(target, words.get(target));
        session = s;
        timeoutTask = server.scheduler().schedule(
                () -> onTimeout(s), Protocol.GAME_DURATION_SEC, TimeUnit.SECONDS);

        // Kata jawaban hanya muncul di log server, tidak pernah dikirim ke client sebelum game selesai.
        server.logger().log(who() + " mulai game, kata: " + s.getTarget());
        return Protocol.build(Protocol.STARTED, String.valueOf(Protocol.GAME_DURATION_SEC));
    }

    private String handleGuess(String line) {
        GameSession s = session;
        if (s == null || s.isFinished()) {
            return Protocol.build(Protocol.ERR, "Tidak ada game yang sedang berjalan");
        }

        String[] p = Protocol.parse(line);
        String guess = p.length > 1 ? p[1].trim().toUpperCase() : "";

        if (!guess.matches("[A-Z]{" + Protocol.WORD_LENGTH + "}")) {
            return Protocol.build(Protocol.INVALID, "Kata harus " + Protocol.WORD_LENGTH + " huruf!");
        }
        if (s.isTimeUp()) {
            // Thread timer yang akan mengirim PUSH_GAMEOVER
            return Protocol.build(Protocol.ERR, "Waktu sudah habis");
        }
        if (!db.wordExist(guess)) {
            return Protocol.build(Protocol.INVALID, "Kata harus ada di KBBI!");
        }

        synchronized (s) {
            if (s.isFinished()) return Protocol.build(Protocol.ERR, "Game sudah selesai");

            String pattern = s.registerGuess(guess);
            boolean win = guess.equals(s.getTarget());

            if (win || s.getAttempts() >= Protocol.MAX_ATTEMPTS) {
                s.tryFinish();
                String[] tail = completeGame(s, win);
                String[] all = new String[3 + tail.length];
                all[0] = Protocol.RESULT;
                all[1] = pattern;
                all[2] = win ? Protocol.WIN : Protocol.LOSE;
                System.arraycopy(tail, 0, all, 3, tail.length);
                return Protocol.build(all);
            }
            return Protocol.build(Protocol.RESULT, pattern, Protocol.CONTINUE, String.valueOf(s.getAttempts()));
        }
    }

    /** Dijalankan oleh thread scheduler tepat 2 menit setelah game dimulai. */
    private void onTimeout(GameSession s) {
        synchronized (s) {
            if (!s.tryFinish()) return; // sudah selesai lebih dulu (menang/kalah/keluar)
            String[] tail = completeGame(s, false);
            server.logger().log("Waktu habis untuk " + who());
            send(Protocol.build(Protocol.PUSH_GAMEOVER, Protocol.LOSE, tail[0], tail[1], tail[2]));
        }
    }

    /**
     * Menyimpan hasil dan mengabarkan ke semua client.
     * Return untuk menang : {skor, durasi, percobaan}
     * Return untuk kalah  : {jawaban, durasi, percobaan}
     */
    private String[] completeGame(GameSession s, boolean win) {
        ScheduledFuture<?> t = timeoutTask;
        if (t != null) t.cancel(false);

        int duration = s.elapsedSeconds();
        int attempts = s.getAttempts();
        int score = win ? s.calculateScore() : 0;

        boolean saved = db.saveResult(playerId, s.getWordId(), duration, attempts, score);
        server.logger().log(who() + (win ? " MENANG" : " KALAH") + " | kata " + s.getTarget()
                + " | skor " + score + " | " + duration + " dtk | " + attempts + " percobaan"
                + (saved ? "" : " | GAGAL DISIMPAN"));

        if (win) {
            server.broadcastInfo(username + " baru saja menang dengan skor " + score + " 🎉");
        } else {
            server.broadcastInfo(username + " baru saja menyelesaikan permainan");
        }
        server.broadcastLeaderboard();

        return win
                ? new String[]{String.valueOf(score), String.valueOf(duration), String.valueOf(attempts)}
                : new String[]{s.getTarget(), String.valueOf(duration), String.valueOf(attempts)};
    }

    private String handleQuit() {
        abandonSession();
        return Protocol.OK;
    }

    /** Game ditinggalkan di tengah jalan (tombol MENU, logout, atau koneksi putus). Tidak disimpan. */
    private void abandonSession() {
        GameSession s = session;
        if (s != null && s.tryFinish()) {
            ScheduledFuture<?> t = timeoutTask;
            if (t != null) t.cancel(false);
            server.logger().log(who() + " meninggalkan game (tidak disimpan)");
        }
        session = null;
    }

    private String handleLastResult() {
        Object[] last = db.getLastGameResult(playerId);
        if (last == null) return Protocol.NONE;
        Timestamp ts = (Timestamp) last[4];
        return Protocol.build(Protocol.LAST,
                (String) last[0],
                String.valueOf(last[1]),
                String.valueOf(last[2]),
                String.valueOf(last[3]),
                String.valueOf(ts == null ? 0 : ts.getTime()),
                String.valueOf(cooldownRemaining()));
    }
}