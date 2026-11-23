import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.sound.sampled.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;

/**
 * Tampilan permainan. Di versi jaringan, client TIDAK tahu kata jawaban.
 * Setiap tebakan dikirim ke server, server membalas pola warna (G/P/A).
 * Waktu habis juga diputuskan server lewat pesan PUSH_GAMEOVER.
 */
public class GamePanel extends JPanel {

    private Main mainApp;

    private JLabel timerLabel;
    private JTextField[][] gridFields;
    private JButton[] letterButtons = new JButton[26];
    private JButton btnEnter;
    private JButton btnBack;

    private JPanel gameContentPanel;

    private final int MAX_ATTEMPTS = Protocol.MAX_ATTEMPTS;
    private final int WORD_LENGTH = Protocol.WORD_LENGTH;

    private int currentAttempt;
    private int currentLetterVal;
    private volatile long gameEndsAt;

    /** true selama server menganggap sesi game masih berjalan. */
    private volatile boolean sessionOpen;
    /** true selama pemain boleh mengetik (false saat waktu lokal habis). */
    private volatile boolean isGameActive;
    /** true saat tebakan sedang dikirim ke server, supaya tidak dobel kirim. */
    private boolean waitingResponse;

    private Thread timerThread;
    private StringBuilder currentGuess = new StringBuilder();
    private Clip musicClip;

    public GamePanel(Main mainApp) {
        this.mainApp = mainApp;
        this.setLayout(new BorderLayout());
        this.setBackground(Theme.BG_COLOR);

        initGameUI();

        this.setFocusable(true);
        this.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (!isGameActive) return;

                int code = e.getKeyCode();
                char keyChar = e.getKeyChar();

                if (code == KeyEvent.VK_ENTER) {
                    handleInput("ENTER");
                } else if (code == KeyEvent.VK_BACK_SPACE) {
                    handleInput("BACK");
                } else if (keyChar >= 'a' && keyChar <= 'z' || keyChar >= 'A' && keyChar <= 'Z') {
                    handleInput(String.valueOf(keyChar).toUpperCase());
                }
            }
        });
    }

    private void initGameUI() {
        gameContentPanel = new JPanel(new BorderLayout());
        gameContentPanel.setBackground(Theme.BG_COLOR);

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(Theme.BG_COLOR);
        headerPanel.setBorder(new EmptyBorder(15, 20, 15, 20));

        JButton btnBackMenu = new JButton("« MENU");
        Theme.styleButton(btnBackMenu);
        btnBackMenu.setFont(new Font("SansSerif", Font.BOLD, 12));
        btnBackMenu.setPreferredSize(new Dimension(80, 30));
        btnBackMenu.addActionListener(e -> stopGameAndReturn());

        timerLabel = new JLabel("02:00", SwingConstants.CENTER);
        timerLabel.setFont(Theme.FONT_TITLE);
        timerLabel.setForeground(Theme.FG_TEXT);

        JLabel dummy = new JLabel();
        dummy.setPreferredSize(new Dimension(80, 30));

        headerPanel.add(btnBackMenu, BorderLayout.WEST);
        headerPanel.add(timerLabel, BorderLayout.CENTER);
        headerPanel.add(dummy, BorderLayout.EAST);

        gameContentPanel.add(headerPanel, BorderLayout.NORTH);

        JPanel centerContainer = new JPanel(new FlowLayout(FlowLayout.CENTER));
        centerContainer.setBackground(Theme.BG_COLOR);

        JPanel gridPanel = new JPanel(new GridLayout(MAX_ATTEMPTS, WORD_LENGTH, 5, 5));
        gridPanel.setBackground(Theme.BG_COLOR);
        gridPanel.setBorder(new EmptyBorder(10, 0, 10, 0));

        gridFields = new JTextField[MAX_ATTEMPTS][WORD_LENGTH];
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            for (int j = 0; j < WORD_LENGTH; j++) {
                JTextField box = new JTextField();
                box.setPreferredSize(new Dimension(50, 50));
                box.setEditable(false);
                box.setFocusable(false);
                box.setFont(Theme.FONT_GRID);
                box.setHorizontalAlignment(JTextField.CENTER);
                box.setBackground(Theme.BG_COLOR);
                box.setForeground(Theme.FG_TEXT);
                box.setBorder(BorderFactory.createLineBorder(Theme.COLOR_BORDER, 2));

                gridFields[i][j] = box;
                gridPanel.add(box);
            }
        }
        centerContainer.add(gridPanel);
        gameContentPanel.add(centerContainer, BorderLayout.CENTER);

        JPanel keyboardPanel = new JPanel();
        keyboardPanel.setLayout(new BoxLayout(keyboardPanel, BoxLayout.Y_AXIS));
        keyboardPanel.setBackground(Theme.BG_COLOR);
        keyboardPanel.setBorder(new EmptyBorder(10, 10, 20, 10));

        String[] row1 = {"Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"};
        String[] row2 = {"A", "S", "D", "F", "G", "H", "J", "K", "L"};
        String[] row3 = {"ENTER", "Z", "X", "C", "V", "B", "N", "M", "BACK"};

        keyboardPanel.add(createKeyboardRow(row1));
        keyboardPanel.add(Box.createVerticalStrut(5));
        keyboardPanel.add(createKeyboardRow(row2));
        keyboardPanel.add(Box.createVerticalStrut(5));
        keyboardPanel.add(createKeyboardRow(row3));

        gameContentPanel.add(keyboardPanel, BorderLayout.SOUTH);

        add(gameContentPanel, BorderLayout.CENTER);
    }

    private JPanel createKeyboardRow(String[] keys) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
        row.setBackground(Theme.BG_COLOR);

        for (String key : keys) {
            JButton btn = new JButton(key.equals("BACK") ? "⌫" : key);
            btn.setFont(new Font("SansSerif", Font.BOLD, 14));
            btn.setForeground(Theme.FG_TEXT);
            btn.setBackground(Theme.COLOR_ABSENT);
            btn.setFocusable(false);
            btn.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));

            if (key.equals("ENTER") || key.equals("BACK")) {
                btn.setPreferredSize(new Dimension(70, 45));
            } else {
                btn.setPreferredSize(new Dimension(45, 45));
            }

            btn.addActionListener(e -> {
                handleInput(key);
                this.requestFocusInWindow();
            });

            if (key.equals("ENTER")) {
                btnEnter = btn;
            } else if (key.equals("BACK")) {
                btnBack = btn;
            } else {
                int index = key.charAt(0) - 'A';
                if (index >= 0 && index < 26) letterButtons[index] = btn;
            }

            row.add(btn);
        }
        return row;
    }

    // ---------------------------------------------------------------------
    // Alur permainan
    // ---------------------------------------------------------------------

    public void onPanelShown() {
        stopLocalGame();
        resetBoard();
        timerLabel.setText("Memuat...");

        // Server yang memutuskan: boleh main atau masih cooldown.
        mainApp.getConnection().requestAsync(r -> {
            switch (r[0]) {
                case Protocol.COOLDOWN:
                    mainApp.showCooldownPanel(Long.parseLong(r[1]));
                    break;
                case Protocol.STARTED:
                    beginGame(Integer.parseInt(r[1]));
                    break;
                default:
                    JOptionPane.showMessageDialog(this, r.length > 1 ? r[1] : "Gagal memulai game",
                            "Error", JOptionPane.ERROR_MESSAGE);
                    mainApp.showPanel("MAIN_MENU");
            }
        }, err -> {
            JOptionPane.showMessageDialog(this, err, "Error Jaringan", JOptionPane.ERROR_MESSAGE);
            mainApp.showPanel("MAIN_MENU");
        }, Protocol.START);
    }

    private void beginGame(int durationSec) {
        currentAttempt = 0;
        currentLetterVal = 0;
        currentGuess.setLength(0);
        waitingResponse = false;
        gameEndsAt = System.currentTimeMillis() + durationSec * 1000L;
        sessionOpen = true;
        isGameActive = true;

        startTimerThread();
        playMusic();
        SwingUtilities.invokeLater(this::requestFocusInWindow);
    }

    private void resetBoard() {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            for (int j = 0; j < WORD_LENGTH; j++) {
                gridFields[i][j].setText("");
                gridFields[i][j].setBackground(Theme.BG_COLOR);
                gridFields[i][j].setBorder(BorderFactory.createLineBorder(Theme.COLOR_BORDER, 2));
            }
        }
        for (JButton b : letterButtons) {
            if (b != null) {
                b.setBackground(Theme.COLOR_ABSENT);
                b.setForeground(Theme.FG_TEXT);
            }
        }
        if (btnEnter != null) btnEnter.setBackground(Theme.COLOR_ABSENT);
        if (btnBack != null) btnBack.setBackground(Theme.COLOR_ABSENT);
        timerLabel.setForeground(Theme.FG_TEXT);
    }

    private void handleInput(String key) {
        if (!isGameActive || waitingResponse) return;

        if (key.equals("ENTER")) {
            if (currentGuess.length() != WORD_LENGTH) {
                JOptionPane.showMessageDialog(this, "Kata harus 5 huruf!", "Info", JOptionPane.WARNING_MESSAGE);
                return;
            }
            waitingResponse = true;
            mainApp.getConnection().requestAsync(
                    this::onGuessResponse,
                    err -> {
                        waitingResponse = false;
                        JOptionPane.showMessageDialog(this, err, "Error Jaringan", JOptionPane.ERROR_MESSAGE);
                    },
                    Protocol.GUESS, currentGuess.toString());

        } else if (key.equals("BACK")) {
            if (currentGuess.length() > 0) {
                currentGuess.setLength(currentGuess.length() - 1);
                currentLetterVal--;
                gridFields[currentAttempt][currentLetterVal].setText("");
            }
        } else if (currentGuess.length() < WORD_LENGTH) {
            currentGuess.append(key);
            gridFields[currentAttempt][currentLetterVal].setText(key);
            currentLetterVal++;
        }
    }

    /** Dijalankan di thread UI setelah server membalas GUESS. */
    private void onGuessResponse(String[] r) {
        waitingResponse = false;

        switch (r[0]) {
            case Protocol.INVALID:
                JOptionPane.showMessageDialog(this, r[1], "Info", JOptionPane.WARNING_MESSAGE);
                return;
            case Protocol.ERR:
                // Biasanya karena waktu sudah habis; PUSH_GAMEOVER akan menutup game.
                if (sessionOpen) {
                    JOptionPane.showMessageDialog(this, r[1], "Info", JOptionPane.WARNING_MESSAGE);
                }
                return;
            case Protocol.RESULT:
                break;
            default:
                return;
        }

        paintRow(r[1]);

        switch (r[2]) {
            case Protocol.CONTINUE:
                currentAttempt++;
                currentLetterVal = 0;
                currentGuess.setLength(0);
                break;
            case Protocol.WIN:
                finishGame(true, r[3], Integer.parseInt(r[4]), Integer.parseInt(r[5]));
                break;
            case Protocol.LOSE:
                finishGame(false, r[3], Integer.parseInt(r[4]), Integer.parseInt(r[5]));
                break;
        }
    }

    /** Pesan dari server saat 2 menit habis: PUSH_GAMEOVER|LOSE|jawaban|durasi|percobaan */
    public void onServerGameOver(String[] p) {
        if (!sessionOpen || p.length < 5) return;
        finishGame(false, p[2], Integer.parseInt(p[3]), Integer.parseInt(p[4]));
    }

    private void paintRow(String pattern) {
        String guess = currentGuess.toString();
        for (int i = 0; i < WORD_LENGTH; i++) {
            Color boxColor;
            Color keyColor;
            switch (pattern.charAt(i)) {
                case 'G': boxColor = Theme.COLOR_CORRECT; keyColor = Theme.COLOR_CORRECT; break;
                case 'P': boxColor = Theme.COLOR_PRESENT; keyColor = Theme.COLOR_PRESENT; break;
                default:  boxColor = Theme.COLOR_ABSENT;  keyColor = Theme.COLOR_DEFAULT; break;
            }
            gridFields[currentAttempt][i].setBackground(boxColor);
            gridFields[currentAttempt][i].setBorder(BorderFactory.createLineBorder(boxColor, 2));
            updateKeyColor(guess.charAt(i), keyColor);
        }
    }

    private void updateKeyColor(char c, Color newColor) {
        int index = c - 'A';
        if (index < 0 || index >= 26 || letterButtons[index] == null) return;
        JButton btn = letterButtons[index];

        Color currentColor = btn.getBackground();
        if (currentColor.equals(Theme.COLOR_CORRECT)) return;
        if (currentColor.equals(Theme.COLOR_PRESENT) && !newColor.equals(Theme.COLOR_CORRECT)) return;

        if (newColor.equals(Theme.COLOR_DEFAULT)) {
            btn.setBackground(new Color(30, 30, 30));
            btn.setForeground(Color.GRAY);
        } else {
            btn.setBackground(newColor);
            btn.setForeground(Color.WHITE);
        }
    }

    /**
     * Untuk menang: info = skor. Untuk kalah: info = kata jawaban.
     * Hasil sudah disimpan oleh server, client hanya menampilkan.
     */
    private void finishGame(boolean isWin, String info, int duration, int attempts) {
        if (!sessionOpen) return;
        sessionOpen = false;
        stopLocalGame();

        String msg = isWin
                ? "🎉 SELAMAT! Kamu Menang! 🎉\n\nSkor: " + info + "\nWaktu: " + duration
                    + " detik\nPercobaan: " + attempts + " kali"
                : "😢 YAHHH KALAH!\n\nJawaban: " + info + "\nWaktu bermain: " + duration
                    + " detik\nPercobaan: " + attempts + " kali";

        JOptionPane.showMessageDialog(this,
                msg + "\n\n⏰ Kamu bisa bermain lagi dalam 5 menit.",
                isWin ? "KAMU MENANG!" : "GAME OVER",
                isWin ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);

        mainApp.showEndGamePanel();
    }

    // ---------------------------------------------------------------------
    // Timer tampilan (thread lokal). Keputusan waktu habis tetap dari server.
    // ---------------------------------------------------------------------

    private void startTimerThread() {
        timerThread = new Thread(() -> {
            while (isGameActive) {
                long remainingMs = gameEndsAt - System.currentTimeMillis();
                int remaining = (int) Math.max(0, (remainingMs + 999) / 1000);
                int min = remaining / 60;
                int sec = remaining % 60;

                SwingUtilities.invokeLater(() -> {
                    timerLabel.setText(String.format("%02d:%02d", min, sec));
                    timerLabel.setForeground(remaining <= 10 ? new Color(255, 100, 100) : Theme.FG_TEXT);
                });

                if (remaining == 0) {
                    isGameActive = false; // kunci input, tunggu PUSH_GAMEOVER dari server
                    SwingUtilities.invokeLater(() -> timerLabel.setText("Waktu habis..."));
                    break;
                }

                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "game-timer");
        timerThread.setDaemon(true);
        timerThread.start();
    }

    private void stopLocalGame() {
        isGameActive = false;
        if (timerThread != null) timerThread.interrupt();
        stopMusic();
    }

    private void stopGameAndReturn() {
        if (sessionOpen) {
            sessionOpen = false;
            mainApp.getConnection().requestAsync(r -> {}, Protocol.QUIT_GAME);
        }
        stopLocalGame();
        mainApp.showPanel("MAIN_MENU");
    }

    // ---------------------------------------------------------------------
    // Musik (audio stream dari file)
    // ---------------------------------------------------------------------

    private void playMusic() {
        try {
            File musicFile = new File("assets/WhoKnows.wav");
            if (musicFile.exists()) {
                AudioInputStream audioStream = AudioSystem.getAudioInputStream(musicFile);
                musicClip = AudioSystem.getClip();
                musicClip.open(audioStream);
                musicClip.loop(Clip.LOOP_CONTINUOUSLY);
                musicClip.start();
            }
        } catch (Exception e) {
            System.err.println("Error playing music: " + e.getMessage());
        }
    }

    private void stopMusic() {
        if (musicClip != null) {
            if (musicClip.isRunning()) musicClip.stop();
            musicClip.close();
            musicClip = null;
        }
    }
}
