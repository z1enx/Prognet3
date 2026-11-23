/**
 * Protokol teks KataKita yang dipakai bersama oleh server dan client.
 *
 * Setiap pesan adalah satu baris teks (diakhiri newline), field dipisah "|".
 *
 * REQUEST (client -> server)          RESPONSE (server -> client)
 * ---------------------------------   ------------------------------------------------
 * LOGIN|username|password             OK|playerId|username   atau  ERR|pesan
 * REGISTER|username|password          OK                     atau  ERR|pesan
 * LOGOUT                              OK
 * START                               STARTED|durasiDetik    atau  COOLDOWN|sisaMs
 * GUESS|KATA                          RESULT|pola|CONTINUE|percobaanKe
 *                                     RESULT|pola|WIN|skor|durasi|percobaan
 *                                     RESULT|pola|LOSE|jawaban|durasi|percobaan
 *                                     INVALID|pesan          atau  ERR|pesan
 * QUIT_GAME                           OK
 * LEADERBOARD                         LB|dataLeaderboard
 * LAST_RESULT                         LAST|kata|durasi|percobaan|skor|epochMs|sisaCooldownMs
 *                                     atau NONE
 *
 * PUSH (server -> client, dikirim kapan saja tanpa diminta)
 * ---------------------------------------------------------
 * PUSH_LB|dataLeaderboard             leaderboard terbaru setelah ada game selesai
 * PUSH_INFO|pesan                     aktivitas pemain lain
 * PUSH_ONLINE|jumlah                  jumlah pemain yang sedang login
 * PUSH_GAMEOVER|LOSE|jawaban|durasi|percobaan   waktu 2 menit habis (diputuskan server)
 *
 * pola = 5 karakter: G (posisi tepat), P (ada tapi salah posisi), A (tidak ada)
 * dataLeaderboard = baris dipisah ";", kolom dipisah ","
 *                   rank,username,percobaan,durasi,skor,epochMs
 */
public final class Protocol {

    private Protocol() {}

    public static final int DEFAULT_PORT = 5000;

    public static final String SEP = "|";
    public static final String SEP_REGEX = "\\|";
    public static final String ROW_SEP = ";";
    public static final String COL_SEP = ",";

    // Request
    public static final String LOGIN = "LOGIN";
    public static final String REGISTER = "REGISTER";
    public static final String LOGOUT = "LOGOUT";
    public static final String START = "START";
    public static final String GUESS = "GUESS";
    public static final String QUIT_GAME = "QUIT_GAME";
    public static final String LEADERBOARD = "LEADERBOARD";
    public static final String LAST_RESULT = "LAST_RESULT";

    // Response
    public static final String OK = "OK";
    public static final String ERR = "ERR";
    public static final String STARTED = "STARTED";
    public static final String COOLDOWN = "COOLDOWN";
    public static final String RESULT = "RESULT";
    public static final String INVALID = "INVALID";
    public static final String LB = "LB";
    public static final String LAST = "LAST";
    public static final String NONE = "NONE";

    // Status di dalam RESULT
    public static final String CONTINUE = "CONTINUE";
    public static final String WIN = "WIN";
    public static final String LOSE = "LOSE";

    // Push
    public static final String PUSH_PREFIX = "PUSH_";
    public static final String PUSH_LB = "PUSH_LB";
    public static final String PUSH_INFO = "PUSH_INFO";
    public static final String PUSH_ONLINE = "PUSH_ONLINE";
    public static final String PUSH_GAMEOVER = "PUSH_GAMEOVER";

    // ---------------------------------------------------------------
    // Mode balapan (semua pemain di lobi menebak kata yang sama)
    //
    // REQUEST                      RESPONSE
    // RACE_JOIN                    OK
    // RACE_LEAVE                   OK
    // RACE_START                   OK  atau  ERR|pesan
    // RACE_GUESS|KATA              RESULT|pola|CONTINUE|percobaanKe
    //                              RESULT|pola|WIN|percobaan
    //                              RESULT|pola|OUT|percobaan   (kesempatan habis)
    //                              INVALID|pesan  atau  ERR|pesan
    //
    // PUSH
    // PUSH_RACE_LOBBY|nama1,nama2|STATUS        STATUS = WAITING / COUNTDOWN / RUNNING
    // PUSH_RACE_COUNTDOWN|detik
    // PUSH_RACE_START|durasiDetik|nama1,nama2   peserta ronde ini
    // PUSH_RACE_PROGRESS|nama|percobaanKe|pola|status   status = CONTINUE / WIN / OUT
    // PUSH_RACE_END|pemenang|jawaban|durasiDetik        pemenang "-" kalau tidak ada
    // ---------------------------------------------------------------
    public static final String RACE_JOIN = "RACE_JOIN";
    public static final String RACE_LEAVE = "RACE_LEAVE";
    public static final String RACE_START = "RACE_START";
    public static final String RACE_GUESS = "RACE_GUESS";

    public static final String OUT = "OUT";
    public static final String LEFT = "LEFT";
    public static final String NO_WINNER = "-";

    public static final String STATE_WAITING = "WAITING";
    public static final String STATE_COUNTDOWN = "COUNTDOWN";
    public static final String STATE_RUNNING = "RUNNING";

    public static final String PUSH_RACE_LOBBY = "PUSH_RACE_LOBBY";
    public static final String PUSH_RACE_COUNTDOWN = "PUSH_RACE_COUNTDOWN";
    public static final String PUSH_RACE_START = "PUSH_RACE_START";
    public static final String PUSH_RACE_PROGRESS = "PUSH_RACE_PROGRESS";
    public static final String PUSH_RACE_END = "PUSH_RACE_END";

    public static final int RACE_COUNTDOWN_SEC = 3;
    public static final int MIN_RACERS = 2;

    // Aturan permainan (satu sumber untuk server dan client)
    public static final int WORD_LENGTH = 5;
    public static final int MAX_ATTEMPTS = 6;
    public static final int GAME_DURATION_SEC = 120;
    public static final long COOLDOWN_MS = 5 * 60 * 1000L;

    /** Username hanya huruf, angka, underscore supaya tidak bentrok dengan pemisah protokol. */
    public static final String USERNAME_REGEX = "[A-Za-z0-9_]{3,20}";

    public static String build(String... parts) {
        return String.join(SEP, parts);
    }

    public static String[] parse(String line) {
        return line.split(SEP_REGEX, -1);
    }

    public static boolean isPush(String line) {
        return line.startsWith(PUSH_PREFIX);
    }
}