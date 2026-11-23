import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.*;

/**
 * Akses database. Di versi jaringan, kelas ini HANYA dipakai oleh server.
 * Client tidak lagi terhubung ke MySQL secara langsung.
 */
public class DBCon {

    /** Password disimpan sebagai hash SHA-256 (hex huruf kecil, 64 karakter). */
    static String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String dbName = "katakita";
    private String dbUrl = "jdbc:mysql://127.0.0.1:3306/" + dbName;
    private String usn = "root";
    private String pass = "";

    // Sebelumnya method ini mengembalikan null saat gagal, yang berujung NullPointerException
    // di setiap pemanggil. Sekarang error dilempar sebagai SQLException supaya ditangkap
    // oleh blok catch yang sudah ada.
    public Connection getConnection() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("Driver MySQL tidak ditemukan di classpath", e);
        }
        String url = dbUrl + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Makassar";
        return DriverManager.getConnection(url, usn, pass);
    }

    /** Return null kalau berhasil, atau pesan error kalau gagal. */
    public String registerPlayer(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return "Username dan password wajib diisi";
        }
        String sql = "INSERT INTO player(username, password) VALUES(?, ?)";
        try (Connection con = getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, hashPassword(password));
            ps.executeUpdate();
            return null;
        } catch (SQLIntegrityConstraintViolationException e) {
            return "Registrasi gagal. Username sudah digunakan.";
        } catch (SQLException e) {
            e.printStackTrace();
            return "Registrasi gagal karena error database: " + e.getMessage();
        }
    }

    public int loginPlayer(String username, String password) {
        if (username == null || username.isBlank() || password == null) return -1;
        String sql = "SELECT player_id FROM player WHERE username = ? AND password = ? LIMIT 1";
        try (Connection con = getConnection();
            PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, hashPassword(password));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("player_id");
                } else {
                    return -1;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return -1;
        }
    }

    public boolean saveResult(int playerId, int wordId, int durations, int totalAttempts, int finalScore) {

        String sql = "INSERT INTO gamesession (player_id, word_id, start_time, duration_seconds, total_attempts, final_score, end_time) VALUES(?, ?, ?, ?, ?, ?, ?)";

        try (Connection con = getConnection();
            PreparedStatement ps = con.prepareStatement(sql)) {

            long endTimeMillis = System.currentTimeMillis();
            long startTimeMillis = endTimeMillis - (durations * 1000);

            ps.setInt(1, playerId);
            ps.setInt(2, wordId);
            ps.setTimestamp(3, new java.sql.Timestamp(startTimeMillis));
            ps.setInt(4, durations);
            ps.setInt(5, totalAttempts);
            ps.setInt(6, finalScore);
            ps.setTimestamp(7, new java.sql.Timestamp(endTimeMillis));

            ps.executeUpdate();
            return true;

        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public Map<String, Integer> getSoal() {
        Map<String, Integer> wordMap = new HashMap<>();
        // Soal hanya dari kata umum (is_target = 1); semua kata tetap sah sebagai tebakan.
        String sql = "SELECT word_id, word_text FROM word WHERE is_target = 1 ORDER BY RAND() LIMIT 1";

        try (Connection con = getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                wordMap.put(rs.getString("word_text"), rs.getInt("word_id"));
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return wordMap;
    }

    public boolean wordExist(String wordSearch) {
        if (wordSearch == null || wordSearch.trim().isEmpty()) {
            return false;
        }

        String sql = "SELECT COUNT(*) FROM word WHERE word_text = ?";

        try (Connection con = getConnection();
            PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, wordSearch.toUpperCase());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return false;
    }

    public List<Object[]> getLeaderboard() {
        List<Object[]> leaderboard = new ArrayList<>();

        String sql = "SELECT p.username, " +
                     "       MAX(gs.final_score) as highest_score, " +
                     "       (SELECT gs2.total_attempts FROM gamesession gs2 " +
                     "        WHERE gs2.player_id = p.player_id AND gs2.final_score = MAX(gs.final_score) " +
                     "        ORDER BY gs2.start_time DESC LIMIT 1) as attempts, " +
                     "       (SELECT gs2.duration_seconds FROM gamesession gs2 " +
                     "        WHERE gs2.player_id = p.player_id AND gs2.final_score = MAX(gs.final_score) " +
                     "        ORDER BY gs2.start_time DESC LIMIT 1) as duration, " +
                     "       (SELECT gs2.start_time FROM gamesession gs2 " +
                     "        WHERE gs2.player_id = p.player_id AND gs2.final_score = MAX(gs.final_score) " +
                     "        ORDER BY gs2.start_time DESC LIMIT 1) as play_time " +
                     "FROM gamesession gs " +
                     "JOIN player p ON gs.player_id = p.player_id " +
                     "GROUP BY p.player_id, p.username " +
                     "ORDER BY highest_score DESC, duration ASC " +
                     "LIMIT 10";

        try (Connection con = getConnection();
            PreparedStatement ps = con.prepareStatement(sql);
            ResultSet rs = ps.executeQuery()) {

            int peringkat = 1;
            while (rs.next()) {
                leaderboard.add(new Object[]{
                    peringkat,
                    rs.getString("username"),
                    rs.getInt("attempts"),
                    rs.getInt("duration"),
                    rs.getInt("highest_score"),
                    rs.getTimestamp("play_time")
                });
                peringkat++;
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return leaderboard;
    }

    public long getLastGameTime(int playerId) {
        String sql = "SELECT MAX(end_time) as last_time FROM gamesession WHERE player_id = ?";
        try (Connection con = getConnection();
            PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    java.sql.Timestamp ts = rs.getTimestamp("last_time");
                    if (ts != null) {
                        return ts.getTime();
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public Object[] getLastGameResult(int playerId) {
        String sql = "SELECT w.word_text, gs.duration_seconds, gs.total_attempts, gs.final_score, gs.start_time " +
                     "FROM gamesession gs " +
                     "JOIN word w ON gs.word_id = w.word_id " +
                     "WHERE gs.player_id = ? " +
                     "ORDER BY gs.start_time DESC LIMIT 1";
        try (Connection con = getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Object[]{
                        rs.getString("word_text"),
                        rs.getInt("duration_seconds"),
                        rs.getInt("total_attempts"),
                        rs.getInt("final_score"),
                        rs.getTimestamp("start_time")
                    };
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Mode balapan
    // ---------------------------------------------------------------------

    /** Hasil satu peserta dalam satu ronde balapan. */
    public static class RaceEntry {
        public final int playerId;
        public final int attempts;
        public final String status; // WIN, LOSE, OUT, LEFT

        public RaceEntry(int playerId, int attempts, String status) {
            this.playerId = playerId;
            this.attempts = attempts;
            this.status = status;
        }
    }

    /**
     * Menyimpan satu ronde ke racesession dan semua pesertanya ke raceresult
     * dalam satu transaksi: kalau satu INSERT gagal, semuanya dibatalkan.
     */
    public boolean saveRaceResult(int wordId, Integer winnerId, long startMillis, long endMillis,
                                  int durationSeconds, List<RaceEntry> entries) {
        String sqlSession = "INSERT INTO racesession (word_id, winner_id, start_time, end_time, "
                + "duration_seconds, total_players) VALUES (?, ?, ?, ?, ?, ?)";
        String sqlResult = "INSERT INTO raceresult (race_id, player_id, total_attempts, status) "
                + "VALUES (?, ?, ?, ?)";

        try (Connection con = getConnection()) {
            con.setAutoCommit(false);
            try (PreparedStatement ps = con.prepareStatement(sqlSession, Statement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, wordId);
                if (winnerId == null) ps.setNull(2, Types.INTEGER);
                else ps.setInt(2, winnerId);
                ps.setTimestamp(3, new Timestamp(startMillis));
                ps.setTimestamp(4, new Timestamp(endMillis));
                ps.setInt(5, durationSeconds);
                ps.setInt(6, entries.size());
                ps.executeUpdate();

                int raceId;
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("race_id tidak terbentuk");
                    raceId = keys.getInt(1);
                }

                try (PreparedStatement pr = con.prepareStatement(sqlResult)) {
                    for (RaceEntry e : entries) {
                        pr.setInt(1, raceId);
                        pr.setInt(2, e.playerId);
                        pr.setInt(3, e.attempts);
                        pr.setString(4, e.status);
                        pr.addBatch();
                    }
                    pr.executeBatch();
                }
                con.commit();
                return true;
            } catch (SQLException e) {
                con.rollback();
                throw e;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }
}