import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mode balapan: beberapa pemain menebak kata yang sama, yang pertama benar menang.
 *
 * Panel ini punya dua tampilan (CardLayout):
 *   LOBBY : daftar pemain yang menunggu + tombol Mulai
 *   BOARD : papan sendiri, papan mini lawan (warna saja), keyboard, timer
 *
 * Hampir semua perubahan tampilan dipicu oleh pesan PUSH dari server,
 * yang diteruskan Main ke method on...() di bawah (selalu di thread UI).
 */
public class RacePanel extends JPanel {

    private static final int ROWS = Protocol.MAX_ATTEMPTS;
    private static final int COLS = Protocol.WORD_LENGTH;

    private final Main mainApp;
    private final CardLayout views = new CardLayout();

    // Lobi
    private final DefaultListModel<String> lobbyModel = new DefaultListModel<>();
    private final JLabel lobbyStatus = new JLabel(" ");
    private final JButton btnStart = new JButton("🏁 MULAI BALAPAN");

    // Papan
    private final JLabel timerLabel = new JLabel("02:00", SwingConstants.CENTER);
    private final JLabel boardStatus = new JLabel(" ", SwingConstants.CENTER);
    private final JTextField[][] grid = new JTextField[ROWS][COLS];
    private final JButton[] letterButtons = new JButton[26];
    private final JPanel opponentsPanel = new JPanel();
    private final Map<String, JPanel[][]> opponentBoards = new LinkedHashMap<>();
    private final Map<String, JLabel> opponentLabels = new LinkedHashMap<>();

    private int currentAttempt;
    private int currentLetter;
    private final StringBuilder currentGuess = new StringBuilder();
    private boolean waitingResponse;

    /** true selama ronde berjalan dan kita peserta. */
    private volatile boolean inRace;
    /** true selama kita boleh mengetik (false setelah kesempatan habis atau waktu lokal habis). */
    private volatile boolean canType;
    /** true selama panel ini sedang dibuka dan kita terdaftar di lobi. */
    private boolean joined;

    private Thread timerThread;
    private volatile long raceEndsAt;
    private Timer countdownTimer;

    public RacePanel(Main mainApp) {
        this.mainApp = mainApp;
        setLayout(views);
        setBackground(Theme.BG_COLOR);

        add(buildLobbyView(), "LOBBY");
        add(buildBoardView(), "BOARD");

        setFocusable(true);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (!canType) return;
                char ch = e.getKeyChar();
                if (e.getKeyCode() == KeyEvent.VK_ENTER) handleInput("ENTER");
                else if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE) handleInput("BACK");
                else if (ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z') {
                    handleInput(String.valueOf(ch).toUpperCase());
                }
            }
        });
    }

    // ---------------------------------------------------------------------
    // Susunan tampilan
    // ---------------------------------------------------------------------

    private JPanel buildLobbyView() {
        JPanel outer = new JPanel(new GridBagLayout());
        outer.setBackground(Theme.BG_COLOR);

        JPanel card = Theme.createRoundedPanel(25);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(35, 50, 35, 50));

        JLabel title = new JLabel("🏁 MODE BALAPAN");
        title.setFont(new Font("SansSerif", Font.BOLD, 28));
        title.setForeground(Theme.BTN_COLOR);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel info = new JLabel("<html><center>Semua pemain menebak kata yang sama.<br>"
                + "Yang pertama menebak benar menang.</center></html>");
        info.setFont(Theme.FONT_NORMAL);
        info.setForeground(Theme.FG_TEXT_SOFT);
        info.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel listTitle = new JLabel("Pemain di lobi:");
        listTitle.setFont(new Font("SansSerif", Font.BOLD, 15));
        listTitle.setForeground(Theme.FG_TEXT);
        listTitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        JList<String> list = new JList<>(lobbyModel);
        list.setFont(Theme.FONT_NORMAL);
        list.setBackground(Theme.PANEL_COLOR);
        list.setForeground(Theme.FG_TEXT);
        list.setFocusable(false);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(260, 150));
        scroll.setMaximumSize(new Dimension(260, 150));
        scroll.setBorder(BorderFactory.createLineBorder(Theme.PANEL_BORDER));
        scroll.setAlignmentX(Component.CENTER_ALIGNMENT);

        lobbyStatus.setFont(new Font("SansSerif", Font.BOLD, 16));
        lobbyStatus.setForeground(Theme.COLOR_PRESENT);
        lobbyStatus.setAlignmentX(Component.CENTER_ALIGNMENT);

        Theme.styleButton(btnStart);
        btnStart.setAlignmentX(Component.CENTER_ALIGNMENT);
        btnStart.addActionListener(e -> requestStart());

        JButton btnBack = new JButton("« KEMBALI KE MENU");
        Theme.styleButton(btnBack);
        btnBack.setAlignmentX(Component.CENTER_ALIGNMENT);
        btnBack.addActionListener(e -> leaveToMenu());

        card.add(title);
        card.add(Box.createVerticalStrut(10));
        card.add(info);
        card.add(Box.createVerticalStrut(20));
        card.add(listTitle);
        card.add(Box.createVerticalStrut(8));
        card.add(scroll);
        card.add(Box.createVerticalStrut(15));
        card.add(lobbyStatus);
        card.add(Box.createVerticalStrut(15));
        card.add(btnStart);
        card.add(Box.createVerticalStrut(10));
        card.add(btnBack);

        outer.add(card);
        return outer;
    }

    private JPanel buildBoardView() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BG_COLOR);

        // Header
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Theme.BG_COLOR);
        header.setBorder(new EmptyBorder(12, 20, 5, 20));

        JButton btnQuit = new JButton("« KELUAR");
        Theme.styleButton(btnQuit);
        btnQuit.setFont(new Font("SansSerif", Font.BOLD, 12));
        btnQuit.setPreferredSize(new Dimension(90, 30));
        btnQuit.addActionListener(e -> {
            int ok = JOptionPane.showConfirmDialog(this, "Keluar dari balapan?", "Keluar",
                    JOptionPane.YES_NO_OPTION);
            if (ok == JOptionPane.YES_OPTION) leaveToMenu();
        });

        timerLabel.setFont(Theme.FONT_TITLE);
        timerLabel.setForeground(Theme.FG_TEXT);

        JLabel dummy = new JLabel();
        dummy.setPreferredSize(new Dimension(90, 30));

        header.add(btnQuit, BorderLayout.WEST);
        header.add(timerLabel, BorderLayout.CENTER);
        header.add(dummy, BorderLayout.EAST);

        boardStatus.setFont(Theme.FONT_SMALL);
        boardStatus.setForeground(Theme.FG_TEXT_SOFT);

        JPanel north = new JPanel(new BorderLayout());
        north.setBackground(Theme.BG_COLOR);
        north.add(header, BorderLayout.NORTH);
        north.add(boardStatus, BorderLayout.SOUTH);
        root.add(north, BorderLayout.NORTH);

        // Papan sendiri
        JPanel gridPanel = new JPanel(new GridLayout(ROWS, COLS, 4, 4));
        gridPanel.setBackground(Theme.BG_COLOR);
        for (int i = 0; i < ROWS; i++) {
            for (int j = 0; j < COLS; j++) {
                JTextField box = new JTextField();
                box.setPreferredSize(new Dimension(44, 44));
                box.setEditable(false);
                box.setFocusable(false);
                box.setFont(Theme.FONT_GRID.deriveFont(26f));
                box.setHorizontalAlignment(JTextField.CENTER);
                box.setBackground(Theme.BG_COLOR);
                box.setForeground(Theme.FG_TEXT);
                box.setBorder(BorderFactory.createLineBorder(Theme.COLOR_BORDER, 2));
                grid[i][j] = box;
                gridPanel.add(box);
            }
        }
        JPanel myBoard = new JPanel(new FlowLayout(FlowLayout.CENTER));
        myBoard.setBackground(Theme.BG_COLOR);
        myBoard.add(gridPanel);

        // Papan lawan
        opponentsPanel.setLayout(new BoxLayout(opponentsPanel, BoxLayout.Y_AXIS));
        opponentsPanel.setBackground(Theme.BG_COLOR);
        JScrollPane oppScroll = new JScrollPane(opponentsPanel);
        oppScroll.setPreferredSize(new Dimension(170, 300));
        oppScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(Theme.PANEL_BORDER), "Lawan",
                0, 0, Theme.FONT_SMALL, Theme.FG_TEXT_SOFT));
        oppScroll.getViewport().setBackground(Theme.BG_COLOR);
        oppScroll.getVerticalScrollBar().setUnitIncrement(12);

        JPanel center = new JPanel(new BorderLayout(10, 0));
        center.setBackground(Theme.BG_COLOR);
        center.setBorder(new EmptyBorder(5, 15, 5, 15));
        center.add(myBoard, BorderLayout.CENTER);
        center.add(oppScroll, BorderLayout.EAST);
        root.add(center, BorderLayout.CENTER);

        // Keyboard
        JPanel keyboard = new JPanel();
        keyboard.setLayout(new BoxLayout(keyboard, BoxLayout.Y_AXIS));
        keyboard.setBackground(Theme.BG_COLOR);
        keyboard.setBorder(new EmptyBorder(5, 10, 15, 10));
        keyboard.add(keyRow(new String[]{"Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"}));
        keyboard.add(Box.createVerticalStrut(4));
        keyboard.add(keyRow(new String[]{"A", "S", "D", "F", "G", "H", "J", "K", "L"}));
        keyboard.add(Box.createVerticalStrut(4));
        keyboard.add(keyRow(new String[]{"ENTER", "Z", "X", "C", "V", "B", "N", "M", "BACK"}));
        root.add(keyboard, BorderLayout.SOUTH);

        return root;
    }

    private JPanel keyRow(String[] keys) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
        row.setBackground(Theme.BG_COLOR);
        for (String key : keys) {
            JButton btn = new JButton(key.equals("BACK") ? "⌫" : key);
            btn.setFont(new Font("SansSerif", Font.BOLD, 13));
            btn.setForeground(Theme.FG_TEXT);
            btn.setBackground(Theme.COLOR_ABSENT);
            btn.setFocusable(false);
            btn.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
            btn.setPreferredSize(new Dimension(key.length() > 1 ? 66 : 40, 40));
            btn.addActionListener(e -> {
                if (canType) handleInput(key);
                requestFocusInWindow();
            });
            if (key.length() == 1) letterButtons[key.charAt(0) - 'A'] = btn;
            row.add(btn);
        }
        return row;
    }

    private void buildOpponentBoards(String[] participants) {
        opponentsPanel.removeAll();
        opponentBoards.clear();
        opponentLabels.clear();

        String me = mainApp.getCurrentUsername();
        for (String name : participants) {
            if (name.isEmpty() || name.equals(me)) continue;

            JLabel label = new JLabel(name);
            label.setFont(new Font("SansSerif", Font.BOLD, 13));
            label.setForeground(Theme.FG_TEXT);
            label.setAlignmentX(Component.CENTER_ALIGNMENT);

            JPanel mini = new JPanel(new GridLayout(ROWS, COLS, 2, 2));
            mini.setBackground(Theme.BG_COLOR);
            JPanel[][] cells = new JPanel[ROWS][COLS];
            for (int i = 0; i < ROWS; i++) {
                for (int j = 0; j < COLS; j++) {
                    JPanel cell = new JPanel();
                    cell.setPreferredSize(new Dimension(16, 16));
                    cell.setBackground(Theme.BG_COLOR);
                    cell.setBorder(BorderFactory.createLineBorder(Theme.COLOR_BORDER));
                    cells[i][j] = cell;
                    mini.add(cell);
                }
            }
            JPanel miniWrap = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
            miniWrap.setBackground(Theme.BG_COLOR);
            miniWrap.add(mini);
            miniWrap.setAlignmentX(Component.CENTER_ALIGNMENT);

            opponentsPanel.add(Box.createVerticalStrut(8));
            opponentsPanel.add(label);
            opponentsPanel.add(Box.createVerticalStrut(4));
            opponentsPanel.add(miniWrap);

            opponentBoards.put(name, cells);
            opponentLabels.put(name, label);
        }
        opponentsPanel.revalidate();
        opponentsPanel.repaint();
    }

    // ---------------------------------------------------------------------
    // Masuk / keluar lobi
    // ---------------------------------------------------------------------

    public void onPanelShown() {
        views.show(this, "LOBBY");
        lobbyStatus.setText("Menghubungkan ke lobi...");
        btnStart.setEnabled(false);
        joined = true;
        mainApp.getConnection().requestAsync(r -> {}, Protocol.RACE_JOIN);
    }

    private void leaveToMenu() {
        stopLocal();
        inRace = false;
        joined = false;
        mainApp.getConnection().requestAsync(r -> {}, Protocol.RACE_LEAVE);
        mainApp.showPanel("MAIN_MENU");
    }

    private void requestStart() {
        btnStart.setEnabled(false);
        mainApp.getConnection().requestAsync(r -> {
            if (Protocol.ERR.equals(r[0])) {
                JOptionPane.showMessageDialog(this, r[1], "Belum bisa mulai", JOptionPane.WARNING_MESSAGE);
                btnStart.setEnabled(lobbyModel.size() >= Protocol.MIN_RACERS);
            }
        }, Protocol.RACE_START);
    }

    // ---------------------------------------------------------------------
    // Pesan PUSH dari server
    // ---------------------------------------------------------------------

    /** PUSH_RACE_LOBBY|nama1,nama2|STATUS */
    public void onLobby(String[] p) {
        if (!joined) return;
        lobbyModel.clear();
        String me = mainApp.getCurrentUsername();
        if (p.length > 1 && !p[1].isEmpty()) {
            for (String n : p[1].split(Protocol.COL_SEP)) {
                lobbyModel.addElement(n.equals(me) ? n + " (kamu)" : n);
            }
        }
        String state = p.length > 2 ? p[2] : Protocol.STATE_WAITING;

        if (Protocol.STATE_RUNNING.equals(state)) {
            if (!inRace) lobbyStatus.setText("Ronde sedang berjalan, tunggu ronde berikutnya");
            btnStart.setEnabled(false);
        } else if (Protocol.STATE_COUNTDOWN.equals(state)) {
            btnStart.setEnabled(false);
        } else {
            boolean enough = lobbyModel.size() >= Protocol.MIN_RACERS;
            lobbyStatus.setText(enough ? "Siap! Tekan Mulai kalau semua sudah masuk"
                    : "Menunggu pemain lain... (minimal " + Protocol.MIN_RACERS + ")");
            btnStart.setEnabled(enough);
        }
    }

    /** PUSH_RACE_COUNTDOWN|detik */
    public void onCountdown(String[] p) {
        if (!joined) return;
        int[] sec = {Integer.parseInt(p[1])};
        lobbyStatus.setText("Mulai dalam " + sec[0] + "...");
        if (countdownTimer != null) countdownTimer.stop();
        countdownTimer = new Timer(1000, e -> {
            sec[0]--;
            if (sec[0] > 0) lobbyStatus.setText("Mulai dalam " + sec[0] + "...");
            else ((Timer) e.getSource()).stop();
        });
        countdownTimer.start();
    }

    /** PUSH_RACE_START|durasi|nama1,nama2 */
    public void onRaceStart(String[] p) {
        if (!joined) return;
        if (countdownTimer != null) countdownTimer.stop();

        String[] participants = p.length > 2 ? p[2].split(Protocol.COL_SEP) : new String[0];
        resetBoard();
        buildOpponentBoards(participants);
        boardStatus.setText(participants.length + " pemain sedang balapan. Tebak secepatnya!");

        currentAttempt = 0;
        currentLetter = 0;
        currentGuess.setLength(0);
        waitingResponse = false;
        inRace = true;
        canType = true;
        raceEndsAt = System.currentTimeMillis() + Integer.parseInt(p[1]) * 1000L;

        views.show(this, "BOARD");
        startTimer();
        SwingUtilities.invokeLater(this::requestFocusInWindow);
    }

    /** PUSH_RACE_PROGRESS|nama|percobaanKe|pola|status */
    public void onProgress(String[] p) {
        if (!inRace || p.length < 5) return;
        JPanel[][] cells = opponentBoards.get(p[1]);
        if (cells == null) return;
        int row = Integer.parseInt(p[2]) - 1;
        String pattern = p[3];
        if (row < 0 || row >= ROWS) return;
        for (int j = 0; j < COLS && j < pattern.length(); j++) {
            Color c = colorFor(pattern.charAt(j));
            cells[row][j].setBackground(c);
            cells[row][j].setBorder(BorderFactory.createLineBorder(c));
        }
        if (Protocol.OUT.equals(p[4])) {
            opponentLabels.get(p[1]).setText(p[1] + " (habis)");
            opponentLabels.get(p[1]).setForeground(Theme.FG_TEXT_SOFT);
        }
    }

    /** PUSH_RACE_END|pemenang|jawaban|durasi */
    public void onRaceEnd(String[] p) {
        if (!joined) return;
        boolean wasRacing = inRace;
        inRace = false;
        stopLocal();
        if (!wasRacing) return; // penonton di lobi: cukup tunggu update lobi

        String winner = p[1];
        String answer = p[2];
        String me = mainApp.getCurrentUsername();

        String msg;
        if (winner.equals(me)) {
            msg = "🏆 KAMU MENANG!\n\nJawaban: " + answer + "\nWaktu: " + p[3] + " detik";
        } else if (Protocol.NO_WINNER.equals(winner)) {
            msg = "⌛ Tidak ada yang berhasil menebak.\n\nJawaban: " + answer;
        } else {
            msg = "🏁 " + winner + " menang lebih dulu!\n\nJawaban: " + answer
                    + "\nWaktu: " + p[3] + " detik";
        }
        JOptionPane.showMessageDialog(this, msg, "Balapan Selesai", JOptionPane.INFORMATION_MESSAGE);

        if (joined) views.show(this, "LOBBY");
    }

    // ---------------------------------------------------------------------
    // Input dan tebakan
    // ---------------------------------------------------------------------

    private void handleInput(String key) {
        if (!canType || waitingResponse) return;

        if (key.equals("ENTER")) {
            if (currentGuess.length() != COLS) {
                JOptionPane.showMessageDialog(this, "Kata harus 5 huruf!", "Info", JOptionPane.WARNING_MESSAGE);
                return;
            }
            waitingResponse = true;
            String guess = currentGuess.toString();
            mainApp.getConnection().requestAsync(
                    r -> onGuessResponse(r, guess),
                    err -> waitingResponse = false,
                    Protocol.RACE_GUESS, guess);
        } else if (key.equals("BACK")) {
            if (currentGuess.length() > 0) {
                currentGuess.setLength(currentGuess.length() - 1);
                currentLetter--;
                grid[currentAttempt][currentLetter].setText("");
            }
        } else if (currentGuess.length() < COLS) {
            currentGuess.append(key);
            grid[currentAttempt][currentLetter].setText(key);
            currentLetter++;
        }
    }

    private void onGuessResponse(String[] r, String guess) {
        waitingResponse = false;
        if (Protocol.INVALID.equals(r[0])) {
            if (inRace) JOptionPane.showMessageDialog(this, r[1], "Info", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!Protocol.RESULT.equals(r[0]) || r.length < 4) return;

        String pattern = r[1];
        int row = Integer.parseInt(r[3]) - 1;
        if (row < 0 || row >= ROWS) return;

        for (int j = 0; j < COLS; j++) {
            Color c = colorFor(pattern.charAt(j));
            grid[row][j].setText(String.valueOf(guess.charAt(j)));
            grid[row][j].setBackground(c);
            grid[row][j].setBorder(BorderFactory.createLineBorder(c, 2));
            updateKeyColor(guess.charAt(j), pattern.charAt(j));
        }

        switch (r[2]) {
            case Protocol.CONTINUE:
                currentAttempt++;
                currentLetter = 0;
                currentGuess.setLength(0);
                break;
            case Protocol.OUT:
                canType = false;
                boardStatus.setText("Kesempatanmu habis. Lihat lawan menyelesaikan ronde...");
                break;
            case Protocol.WIN:
                canType = false;
                break;
        }
    }

    // ---------------------------------------------------------------------
    // Bantuan tampilan
    // ---------------------------------------------------------------------

    private static Color colorFor(char c) {
        switch (c) {
            case 'G': return Theme.COLOR_CORRECT;
            case 'P': return Theme.COLOR_PRESENT;
            default:  return Theme.COLOR_ABSENT;
        }
    }

    private void updateKeyColor(char ch, char result) {
        int idx = ch - 'A';
        if (idx < 0 || idx >= 26 || letterButtons[idx] == null) return;
        JButton btn = letterButtons[idx];
        Color cur = btn.getBackground();
        if (cur.equals(Theme.COLOR_CORRECT)) return;
        if (cur.equals(Theme.COLOR_PRESENT) && result != 'G') return;

        if (result == 'A') {
            btn.setBackground(new Color(30, 30, 30));
            btn.setForeground(Color.GRAY);
        } else {
            btn.setBackground(colorFor(result));
            btn.setForeground(Color.WHITE);
        }
    }

    private void resetBoard() {
        for (int i = 0; i < ROWS; i++) {
            for (int j = 0; j < COLS; j++) {
                grid[i][j].setText("");
                grid[i][j].setBackground(Theme.BG_COLOR);
                grid[i][j].setBorder(BorderFactory.createLineBorder(Theme.COLOR_BORDER, 2));
            }
        }
        for (JButton b : letterButtons) {
            if (b != null) {
                b.setBackground(Theme.COLOR_ABSENT);
                b.setForeground(Theme.FG_TEXT);
            }
        }
        timerLabel.setForeground(Theme.FG_TEXT);
    }

    /** Thread timer tampilan. Keputusan waktu habis tetap dari server (PUSH_RACE_END). */
    private void startTimer() {
        if (timerThread != null) timerThread.interrupt();
        timerThread = new Thread(() -> {
            while (inRace) {
                long ms = raceEndsAt - System.currentTimeMillis();
                int remaining = (int) Math.max(0, (ms + 999) / 1000);
                SwingUtilities.invokeLater(() -> {
                    timerLabel.setText(String.format("%02d:%02d", remaining / 60, remaining % 60));
                    timerLabel.setForeground(remaining <= 10 ? new Color(255, 100, 100) : Theme.FG_TEXT);
                });
                if (remaining == 0) {
                    canType = false;
                    break;
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "race-timer");
        timerThread.setDaemon(true);
        timerThread.start();
    }

    private void stopLocal() {
        canType = false;
        if (timerThread != null) timerThread.interrupt();
        if (countdownTimer != null) countdownTimer.stop();
    }
}