import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Lobi dan ronde mode balapan. Satu objek untuk seluruh server.
 *
 * Diakses bersamaan oleh banyak thread ClientHandler (join, start, guess, leave)
 * dan oleh thread scheduler (hitung mundur dan batas waktu). Semua perubahan state
 * dilakukan di dalam method synchronized, jadi hanya satu thread yang mengubah
 * isi lobi atau ronde pada satu waktu.
 *
 * Hasil balapan disimpan ke tabel racesession dan raceresult (bukan gamesession),
 * sehingga tidak memicu cooldown mode solo dan tidak masuk leaderboard solo.
 * Penyimpanan dijalankan di thread db-writer supaya lock lobi tidak tertahan
 * selama query database berjalan.
 */
public class RaceRoom {

    private enum State { WAITING, COUNTDOWN, RUNNING }

    private static class Racer {
        final int playerId;
        final String username;
        int attempts = 0;
        boolean out = false;

        Racer(int playerId, String username) {
            this.playerId = playerId;
            this.username = username;
        }
    }

    private final KataKitaServer server;
    private final DBCon db = new DBCon();

    private final Set<ClientHandler> lobby = new LinkedHashSet<>();
    private final Map<ClientHandler, Racer> racers = new LinkedHashMap<>();
    /** Peserta yang keluar di tengah ronde, tetap dicatat dengan status LEFT. */
    private final List<Racer> departed = new ArrayList<>();

    private State state = State.WAITING;
    private String target;
    private int targetWordId;
    private long startMillis;
    private ScheduledFuture<?> countdownTask;
    private ScheduledFuture<?> timeoutTask;

    public RaceRoom(KataKitaServer server) {
        this.server = server;
    }

    // ---------------------------------------------------------------------
    // Lobi
    // ---------------------------------------------------------------------

    public synchronized String join(ClientHandler c) {
        lobby.add(c);
        server.logger().log(c.getUsername() + " masuk lobi balapan (" + lobby.size() + " pemain)");
        broadcastLobby();
        return Protocol.OK;
    }

    public synchronized void leave(ClientHandler c) {
        boolean wasInLobby = lobby.remove(c);
        Racer r = racers.remove(c);

        if (state == State.COUNTDOWN && lobby.size() < Protocol.MIN_RACERS) {
            if (countdownTask != null) countdownTask.cancel(false);
            state = State.WAITING;
            server.logger().log("Hitung mundur balapan dibatalkan, pemain kurang");
        }
        if (state == State.RUNNING && r != null) {
            departed.add(r);
            server.logger().log(c.getUsername() + " keluar di tengah balapan");
            if (racers.isEmpty() || allOut()) endRace(null);
        }
        if (wasInLobby) {
            server.logger().log(c.getUsername() + " keluar dari lobi balapan");
            broadcastLobby();
        }
    }

    public synchronized String start(ClientHandler c) {
        if (!lobby.contains(c)) return Protocol.build(Protocol.ERR, "Masuk lobi dulu");
        if (state != State.WAITING) return Protocol.build(Protocol.ERR, "Balapan sedang berjalan");
        if (lobby.size() < Protocol.MIN_RACERS) {
            return Protocol.build(Protocol.ERR, "Butuh minimal " + Protocol.MIN_RACERS + " pemain di lobi");
        }

        state = State.COUNTDOWN;
        server.logger().log(c.getUsername() + " memulai hitung mundur balapan");
        sendToLobby(Protocol.build(Protocol.PUSH_RACE_COUNTDOWN, String.valueOf(Protocol.RACE_COUNTDOWN_SEC)));
        broadcastLobby();
        countdownTask = server.scheduler().schedule(this::beginRace, Protocol.RACE_COUNTDOWN_SEC, TimeUnit.SECONDS);
        return Protocol.OK;
    }

    /** Dijalankan thread scheduler setelah hitung mundur selesai. */
    private synchronized void beginRace() {
        if (state != State.COUNTDOWN) return;
        if (lobby.size() < Protocol.MIN_RACERS) {
            state = State.WAITING;
            broadcastLobby();
            return;
        }

        Map<String, Integer> words = db.getSoal();
        if (words.isEmpty()) words.put("HEBAT", 1);
        List<String> list = new ArrayList<>(words.keySet());
        String picked = list.get(new Random().nextInt(list.size()));
        target = picked.toUpperCase();
        targetWordId = words.get(picked);

        racers.clear();
        departed.clear();
        for (ClientHandler c : lobby) racers.put(c, new Racer(c.getPlayerId(), c.getUsername()));

        state = State.RUNNING;
        startMillis = System.currentTimeMillis();
        timeoutTask = server.scheduler().schedule(this::onTimeout, Protocol.GAME_DURATION_SEC, TimeUnit.SECONDS);

        server.logger().log("Balapan dimulai, " + racers.size() + " peserta, kata: " + target);
        sendToRacers(Protocol.build(Protocol.PUSH_RACE_START,
                String.valueOf(Protocol.GAME_DURATION_SEC), names(racers.keySet())));
        broadcastLobby();
    }

    // ---------------------------------------------------------------------
    // Tebakan
    // ---------------------------------------------------------------------

    public String guess(ClientHandler c, String rawGuess) {
        String guess = rawGuess == null ? "" : rawGuess.trim().toUpperCase();
        if (!guess.matches("[A-Z]{" + Protocol.WORD_LENGTH + "}")) {
            return Protocol.build(Protocol.INVALID, "Kata harus " + Protocol.WORD_LENGTH + " huruf!");
        }
        // Query database dilakukan di luar lock supaya tebakan pemain lain tidak ikut menunggu.
        if (!db.wordExist(guess)) {
            return Protocol.build(Protocol.INVALID, "Kata harus ada di KBBI!");
        }

        synchronized (this) {
            if (state != State.RUNNING) return Protocol.build(Protocol.ERR, "Tidak ada balapan yang berjalan");
            Racer r = racers.get(c);
            if (r == null) return Protocol.build(Protocol.ERR, "Kamu tidak ikut ronde ini");
            if (r.out) return Protocol.build(Protocol.ERR, "Kesempatanmu sudah habis");

            r.attempts++;
            String pattern = GameSession.evaluate(target, guess);
            boolean win = guess.equals(target);
            if (!win && r.attempts >= Protocol.MAX_ATTEMPTS) r.out = true;

            String status = win ? Protocol.WIN : (r.out ? Protocol.OUT : Protocol.CONTINUE);

            // Lawan hanya menerima pola warna, bukan hurufnya.
            sendToRacersExcept(c, Protocol.build(Protocol.PUSH_RACE_PROGRESS,
                    c.getUsername(), String.valueOf(r.attempts), pattern, status));

            String response = Protocol.build(Protocol.RESULT, pattern, status, String.valueOf(r.attempts));

            if (win) {
                c.send(response); // pastikan pemenang menerima hasil tebakannya sebelum pengumuman
                endRace(c);
                return null;
            }
            if (allOut()) {
                c.send(response);
                endRace(null);
                return null;
            }
            return response;
        }
    }

    private synchronized void onTimeout() {
        if (state == State.RUNNING) {
            server.logger().log("Waktu balapan habis");
            endRace(null);
        }
    }

    /** Harus dipanggil saat memegang lock objek ini. */
    private void endRace(ClientHandler winner) {
        if (timeoutTask != null) timeoutTask.cancel(false);
        long endMillis = System.currentTimeMillis();
        int duration = (int) Math.min(Protocol.GAME_DURATION_SEC, (endMillis - startMillis) / 1000);

        saveResultAsync(winner, endMillis, duration);

        String winnerName = winner == null ? Protocol.NO_WINNER : winner.getUsername();
        String msg = Protocol.build(Protocol.PUSH_RACE_END, winnerName, target, String.valueOf(duration));

        Set<ClientHandler> audience = new LinkedHashSet<>(racers.keySet());
        audience.addAll(lobby);
        for (ClientHandler c : audience) c.send(msg);

        server.logger().log("Balapan selesai | pemenang: " + winnerName + " | kata: " + target
                + " | " + duration + " dtk");
        if (winner != null) {
            server.broadcastInfo(winnerName + " memenangkan balapan dalam " + duration + " detik 🏁");
        }

        racers.clear();
        departed.clear();
        state = State.WAITING;
        broadcastLobby();
    }

    /**
     * Menyalin data ronde lalu menyimpannya di thread db-writer.
     * Status: WIN (menang), OUT (6 kesempatan habis), LOSE (masih main saat ronde berakhir),
     * LEFT (keluar di tengah ronde).
     */
    private void saveResultAsync(ClientHandler winner, long endMillis, int duration) {
        Racer winnerRacer = winner == null ? null : racers.get(winner);
        List<DBCon.RaceEntry> entries = new ArrayList<>();
        for (Racer r : racers.values()) {
            String status = r == winnerRacer ? Protocol.WIN : (r.out ? Protocol.OUT : Protocol.LOSE);
            entries.add(new DBCon.RaceEntry(r.playerId, r.attempts, status));
        }
        for (Racer r : departed) {
            entries.add(new DBCon.RaceEntry(r.playerId, r.attempts, Protocol.LEFT));
        }

        final int wordId = targetWordId;
        final Integer winnerId = winnerRacer == null ? null : winnerRacer.playerId;
        final long start = startMillis;
        final String word = target;

        server.runDbTask(() -> {
            boolean ok = db.saveRaceResult(wordId, winnerId, start, endMillis, duration, entries);
            server.logger().log("Hasil balapan (" + word + ", " + entries.size() + " peserta) "
                    + (ok ? "tersimpan" : "GAGAL disimpan"));
        });
    }

    // ---------------------------------------------------------------------
    // Bantuan
    // ---------------------------------------------------------------------

    private boolean allOut() {
        for (Racer r : racers.values()) if (!r.out) return false;
        return true;
    }

    private void broadcastLobby() {
        sendToLobby(Protocol.build(Protocol.PUSH_RACE_LOBBY, names(lobby), stateName()));
    }

    private String stateName() {
        switch (state) {
            case COUNTDOWN: return Protocol.STATE_COUNTDOWN;
            case RUNNING:   return Protocol.STATE_RUNNING;
            default:        return Protocol.STATE_WAITING;
        }
    }

    private void sendToLobby(String msg) {
        for (ClientHandler c : lobby) c.send(msg);
    }

    private void sendToRacers(String msg) {
        for (ClientHandler c : racers.keySet()) c.send(msg);
    }

    private void sendToRacersExcept(ClientHandler except, String msg) {
        for (ClientHandler c : racers.keySet()) if (c != except) c.send(msg);
    }

    private static String names(Iterable<ClientHandler> clients) {
        StringBuilder sb = new StringBuilder();
        for (ClientHandler c : clients) {
            if (sb.length() > 0) sb.append(Protocol.COL_SEP);
            sb.append(c.getUsername());
        }
        return sb.toString();
    }
}