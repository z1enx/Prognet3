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
