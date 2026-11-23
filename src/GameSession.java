/**
 * Satu sesi permainan milik satu pemain. Disimpan di server, sehingga client
 * tidak pernah tahu kata jawabannya.
 *
 * Objek ini bisa diakses oleh dua thread: thread ClientHandler (saat pemain menebak)
 * dan thread scheduler (saat waktu 2 menit habis). Karena itu state-nya dijaga
 * dengan synchronized.
 */
public class GameSession {

    private final String target;
    private final int wordId;
    private final long startMillis;

    private int attempts = 0;
    private boolean finished = false;

    public GameSession(String target, int wordId) {
        this.target = target.toUpperCase();
        this.wordId = wordId;
        this.startMillis = System.currentTimeMillis();
    }

    public String getTarget() { return target; }
    public int getWordId() { return wordId; }

    public synchronized int getAttempts() { return attempts; }
    public synchronized boolean isFinished() { return finished; }

    /**
     * Menandai sesi selesai. Mengembalikan true hanya untuk pemanggil pertama,
     * jadi hasil game tidak akan tersimpan dua kali walaupun "menang" dan
     * "waktu habis" terjadi hampir bersamaan.
     */
    public synchronized boolean tryFinish() {
        if (finished) return false;
        finished = true;
        return true;
    }

    /** Mencatat satu tebakan dan mengembalikan pola warnanya. */
    public synchronized String registerGuess(String guess) {
        attempts++;
        return evaluate(target, guess);
    }

    public int elapsedSeconds() {
        long sec = (System.currentTimeMillis() - startMillis) / 1000;
        return (int) Math.min(sec, Protocol.GAME_DURATION_SEC);
    }

    public int remainingSeconds() {
        return Protocol.GAME_DURATION_SEC - elapsedSeconds();
    }

    public boolean isTimeUp() {
        return System.currentTimeMillis() - startMillis >= Protocol.GAME_DURATION_SEC * 1000L;
    }

    /** Rumus skor sama dengan versi PBO: sisa waktu x 10 + sisa kesempatan x 50. */
    public synchronized int calculateScore() {
        int attemptsLeft = Protocol.MAX_ATTEMPTS - (attempts - 1);
        return remainingSeconds() * 10 + attemptsLeft * 50;
    }

    /**
     * Pewarnaan ala Wordle dengan dua putaran.
     * Putaran 1: tandai huruf yang posisinya tepat (G), sisanya dihitung sebagai stok huruf.
     * Putaran 2: huruf yang belum G diberi P kalau stok huruf itu masih ada, selain itu A.
     *
     * Versi PBO memakai satu putaran, sehingga huruf kembar bisa salah warna.
     */
    public static String evaluate(String target, String guess) {
        int n = target.length();
        char[] result = new char[n];
        int[] stock = new int[26];

        for (int i = 0; i < n; i++) {
            if (guess.charAt(i) == target.charAt(i)) {
                result[i] = 'G';
            } else {
                result[i] = 'A';
                stock[target.charAt(i) - 'A']++;
            }
        }
        for (int i = 0; i < n; i++) {
            if (result[i] == 'G') continue;
            int idx = guess.charAt(i) - 'A';
            if (stock[idx] > 0) {
                result[i] = 'P';
                stock[idx]--;
            }
        }
        return new String(result);
    }
}
