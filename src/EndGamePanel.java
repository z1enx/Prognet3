import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;

public class EndGamePanel extends JPanel {

    private Main mainApp;

    private JLabel cooldownTimerLabel;
    private JPanel statusPanel;

    private volatile long cooldownEndsAt;
    private volatile boolean isRunning;
    private Thread cooldownThread;

    public EndGamePanel(Main mainApp) {
        this.mainApp = mainApp;

        setLayout(new GridBagLayout());
        setBackground(Theme.BG_COLOR);

        initUI();
    }

    private void initUI() {
        JPanel card = Theme.createRoundedPanel(25);
        card.setOpaque(false);
        card.setBorder(new EmptyBorder(40, 60, 40, 60));
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

        JLabel titleLabel = new JLabel("🎮 HASIL GAME TERAKHIR 🎮");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 28));
        titleLabel.setForeground(Theme.BTN_COLOR);
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        statusPanel = Theme.createRoundedPanel(20);
        statusPanel.setLayout(new BoxLayout(statusPanel, BoxLayout.Y_AXIS));
        statusPanel.setBorder(new EmptyBorder(22, 28, 22, 28));
        statusPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
        statusPanel.setMaximumSize(new Dimension(420, 270));

        addStatusLine("Status", "-");
        addStatusLine("Kata", "-");
        addStatusLine("Percobaan", "-");
        addStatusLine("Durasi", "-");
        addStatusLine("Skor", "-");

        JLabel timeLabel = new JLabel("-");
        timeLabel.setName("timeLabel");
        timeLabel.setFont(new Font("SansSerif", Font.ITALIC, 13));
        timeLabel.setForeground(Theme.FG_TEXT_SOFT);
        timeLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);

        statusPanel.add(Box.createVerticalStrut(12));
        statusPanel.add(timeLabel);

        JLabel cooldownTitle = new JLabel("⏰ Kamu bisa bermain lagi dalam:");
        cooldownTitle.setFont(new Font("SansSerif", Font.BOLD, 18));
        cooldownTitle.setForeground(Theme.FG_TEXT_SOFT);
        cooldownTitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        cooldownTimerLabel = new JLabel("05:00");
        cooldownTimerLabel.setFont(new Font("SansSerif", Font.BOLD, 60));
        cooldownTimerLabel.setForeground(Theme.COLOR_PRESENT);
        cooldownTimerLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JButton btnBack = new JButton("MENU UTAMA");
        Theme.styleButton(btnBack);
        btnBack.setAlignmentX(Component.CENTER_ALIGNMENT);

        btnBack.addActionListener(e -> {
            stopCooldown();
            mainApp.showPanel("MAIN_MENU");
        });

        card.add(titleLabel);
        card.add(Box.createVerticalStrut(30));
        card.add(statusPanel);
        card.add(Box.createVerticalStrut(38));
        card.add(cooldownTitle);
        card.add(Box.createVerticalStrut(12));
        card.add(cooldownTimerLabel);
        card.add(Box.createVerticalStrut(45));
        card.add(btnBack);

        add(card);
    }

    private void addStatusLine(String label, String value) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);

        JLabel lblLeft = new JLabel(label + ":");
        lblLeft.setFont(new Font("SansSerif", Font.BOLD, 16));
        lblLeft.setForeground(Theme.FG_TEXT);

        JLabel lblRight = new JLabel(value);
        lblRight.setFont(new Font("SansSerif", Font.PLAIN, 16));
        lblRight.setForeground(Theme.FG_TEXT);
        lblRight.setName(label);

        row.add(lblLeft, BorderLayout.WEST);
        row.add(lblRight, BorderLayout.EAST);

        statusPanel.add(row);
        statusPanel.add(Box.createVerticalStrut(10));
    }

    public void onPanelShown() {
        stopCooldown();
        mainApp.getConnection().requestAsync(r -> {
            if (Protocol.LAST.equals(r[0]) && r.length >= 7) {
                showLastGameResult(r);
                long remaining = Long.parseLong(r[6]);
                cooldownEndsAt = System.currentTimeMillis() + remaining;
                if (remaining > 0) {
                    startCooldownTimer();
                } else {
                    cooldownTimerLabel.setText("00:00");
                }
            } else {
                cooldownTimerLabel.setText("00:00");
            }
        }, Protocol.LAST_RESULT);
    }

    /** r = LAST|kata|durasi|percobaan|skor|epochMs|sisaCooldownMs */
    private void showLastGameResult(String[] r) {
        String kata = r[1];
        int durasi = Integer.parseInt(r[2]);
        int perc = Integer.parseInt(r[3]);
        int skor = Integer.parseInt(r[4]);
        long epoch = Long.parseLong(r[5]);

        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
        String dateStr = epoch > 0 ? sdf.format(new Date(epoch)) : "-";

        if(skor == 0) setStatusValue("Status", "Kalah");
        else setStatusValue("Status", "Menang");
        setStatusValue("Kata", kata);
        setStatusValue("Percobaan", perc + "");
        setStatusValue("Durasi", durasi + " detik");
        setStatusValue("Skor", skor + "");

        for (Component c : statusPanel.getComponents()) {
            if (c instanceof JLabel && "timeLabel".equals(c.getName())) {
                ((JLabel) c).setText(dateStr);
            }
        }
    }

    private void setStatusValue(String field, String value) {
        for (Component c : statusPanel.getComponents()) {
            if (c instanceof JPanel) {
                JPanel row = (JPanel) c;
                Component[] children = row.getComponents();
                if (children.length == 2 && children[1] instanceof JLabel) {
                    JLabel right = (JLabel) children[1];
                    if (field.equals(right.getName())) {
                        right.setText(value);
                    }
                }
            }
        }
    }

    private void startCooldownTimer() {
        stopCooldown();
        isRunning = true;

        cooldownThread = new Thread(() -> {
            while (isRunning) {

                long remaining = cooldownEndsAt - System.currentTimeMillis();
                if (remaining < 0) remaining = 0;

                int min = (int)(remaining / 60000);
                int sec = (int)((remaining % 60000) / 1000);

                SwingUtilities.invokeLater(() ->
                        cooldownTimerLabel.setText(String.format("%02d:%02d", min, sec)));

                if (remaining <= 0) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this,
                                "⏰ Cooldown selesai! Kamu bisa bermain lagi.",
                                "Info", JOptionPane.INFORMATION_MESSAGE);
                        mainApp.showPanel("GAME");
                    });
                    break;
                }

                try { Thread.sleep(1000); }
                catch (InterruptedException e) { break; }
            }
        });

        cooldownThread.start();
    }

    public void stopCooldown() {
        isRunning = false;
        if (cooldownThread != null) cooldownThread.interrupt();
    }
}
