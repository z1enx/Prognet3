import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

public class Main extends JFrame {

    private static final String CONFIG_FILE = "katakita-client.properties";

    private final ServerConnection conn;

    private CardLayout cardLayout;
    private JPanel mainPanelContainer;

    private LoginPanel loginPanel = new LoginPanel(this);
    private MainMenuPanel mainMenuPanel = new MainMenuPanel(this);
    private GamePanel gamePanel = new GamePanel(this);
    private LeaderboardPanel leaderboardPanel = new LeaderboardPanel(this);
    private CooldownPanel cooldownPanel = new CooldownPanel(this);
    private EndGamePanel endGamePanel = new EndGamePanel(this);

    private int currentUserId = -1;
    private String currentUsername;

    public Main(ServerConnection conn, String serverAddress) {
        this.conn = conn;

        setTitle("KataKita: Tebak kata secepat mungkin! [server " + serverAddress + "]");
        getContentPane().setBackground(Theme.BG_COLOR);
        setSize(700, 650);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        cardLayout = new CardLayout();
        mainPanelContainer = new JPanel(cardLayout);
        mainPanelContainer.setOpaque(false);

        JPanel loginWrapper = new JPanel(new GridBagLayout());
        loginWrapper.setOpaque(false);
        loginWrapper.add(loginPanel);

        // Nama kartu diperbaiki: di versi lama "ENDGAME" dan "COOLDOWN" tertukar.
        mainPanelContainer.add(loginWrapper, "LOGIN");
        mainPanelContainer.add(mainMenuPanel, "MAIN_MENU");
        mainPanelContainer.add(gamePanel, "GAME");
        mainPanelContainer.add(leaderboardPanel, "LEADERBOARD");
        mainPanelContainer.add(cooldownPanel, "COOLDOWN");
        mainPanelContainer.add(endGamePanel, "ENDGAME");

        add(mainPanelContainer);

        registerPushListeners();

        conn.setDefaultErrorHandler(msg -> JOptionPane.showMessageDialog(this,
                "Gagal berkomunikasi dengan server:\n" + msg, "Error Jaringan", JOptionPane.ERROR_MESSAGE));

        conn.setOnDisconnect(() -> {
            JOptionPane.showMessageDialog(this,
                    "Koneksi ke server terputus. Aplikasi akan ditutup.",
                    "Terputus", JOptionPane.ERROR_MESSAGE);
            System.exit(0);
        });

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                conn.close();
            }
        });

        showPanel("LOGIN");
    }

    /** Pesan yang dikirim server tanpa diminta (real-time). */
    private void registerPushListeners() {
        conn.onPush(Protocol.PUSH_LB, p -> leaderboardPanel.updateFromPayload(p.length > 1 ? p[1] : ""));
        conn.onPush(Protocol.PUSH_INFO, p -> mainMenuPanel.setActivity(p.length > 1 ? p[1] : ""));
        conn.onPush(Protocol.PUSH_ONLINE, p -> mainMenuPanel.setOnlineCount(p.length > 1 ? p[1] : "0"));
        conn.onPush(Protocol.PUSH_GAMEOVER, p -> gamePanel.onServerGameOver(p));
    }

    public ServerConnection getConnection() {
        return conn;
    }

    public void showPanel(String panelName) {
        if (panelName.equals("GAME")) {
            gamePanel.onPanelShown();
        } else if (panelName.equals("LEADERBOARD")) {
            leaderboardPanel.onPanelShown();
        }
        cardLayout.show(mainPanelContainer, panelName);
    }

    public void showCooldownPanel(long remainingMs) {
        cardLayout.show(mainPanelContainer, "COOLDOWN");
        cooldownPanel.onPanelShown(remainingMs);
    }

    public void showEndGamePanel() {
        cardLayout.show(mainPanelContainer, "ENDGAME");
        endGamePanel.onPanelShown();
    }

    public void onLoginSuccess(int userId, String username) {
        this.currentUserId = userId;
        this.currentUsername = username;
        mainMenuPanel.setWelcomeMessage(username);
        showPanel("MAIN_MENU");
    }

    public void logout() {
        conn.requestAsync(r -> {}, Protocol.LOGOUT);
        currentUserId = -1;
        currentUsername = null;
        showPanel("LOGIN");
    }

    public int getCurrentUserId() {
        return currentUserId;
    }

    public String getCurrentUsername() {
        return currentUsername;
    }

    // ---------------------------------------------------------------------
    // Alamat server terakhir disimpan ke file (byte stream: FileInputStream/FileOutputStream)
    // ---------------------------------------------------------------------

    private static String loadLastServer() {
        Properties props = new Properties();
        try (InputStream is = new FileInputStream(CONFIG_FILE)) {
            props.load(is);
        } catch (IOException ignored) {
            // file belum ada saat pertama kali dijalankan
        }
        return props.getProperty("server", "localhost:" + Protocol.DEFAULT_PORT);
    }

    private static void saveLastServer(String address) {
        Properties props = new Properties();
        props.setProperty("server", address);
        try (OutputStream os = new FileOutputStream(CONFIG_FILE)) {
            props.store(os, "Konfigurasi client KataKita");
        } catch (IOException e) {
            System.err.println("Gagal menyimpan konfigurasi: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            String address = loadLastServer();

            while (true) {
                address = JOptionPane.showInputDialog(null,
                        "Alamat server (IP:port)\nContoh: 192.168.1.10:5000",
                        address);
                if (address == null) System.exit(0); // tombol Cancel

                address = address.trim();
                String host = address;
                int port = Protocol.DEFAULT_PORT;
                int colon = address.lastIndexOf(':');
                if (colon > 0) {
                    host = address.substring(0, colon);
                    try {
                        port = Integer.parseInt(address.substring(colon + 1));
                    } catch (NumberFormatException e) {
                        JOptionPane.showMessageDialog(null, "Port tidak valid.");
                        continue;
                    }
                }

                try {
                    ServerConnection conn = new ServerConnection(host, port);
                    saveLastServer(address);
                    new Main(conn, address).setVisible(true);
                    return;
                } catch (IOException e) {
                    JOptionPane.showMessageDialog(null,
                            "Tidak bisa terhubung ke " + address + "\n" + e.getMessage(),
                            "Koneksi Gagal", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
    }
}
