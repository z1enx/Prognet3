import javax.swing.*;
import java.awt.*;

public class MainMenuPanel extends JPanel {

    private Main mainApp;
    private JLabel welcomeLabel = new JLabel("Welcome, User!");
    private JLabel onlineLabel = new JLabel(" ");
    private JLabel activityLabel = new JLabel(" ");

    public MainMenuPanel(Main mainApp) {
        this.mainApp = mainApp;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(80, 50, 50, 50));

        welcomeLabel.setFont(Theme.FONT_TITLE);
        welcomeLabel.setForeground(Theme.BTN_COLOR);
        welcomeLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        welcomeLabel.setHorizontalAlignment(SwingConstants.CENTER);
        welcomeLabel.setPreferredSize(new Dimension(500, 40));
        welcomeLabel.setMaximumSize(new Dimension(500, 40));

        JButton startGameButton = createStyledButton("Mulai Main");
        JButton raceButton = createStyledButton("Mode Balapan");
        JButton leaderboardButton = createStyledButton("Lihat Leaderboard");
        JButton logoutButton = createStyledButton("Logout");

        logoutButton.addActionListener(e -> mainApp.logout());
        startGameButton.addActionListener(e -> mainApp.showPanel("GAME"));
        leaderboardButton.addActionListener(e -> mainApp.showPanel("LEADERBOARD"));
        raceButton.addActionListener(e -> mainApp.showPanel("RACE"));

        add(welcomeLabel);
        add(Box.createRigidArea(new Dimension(0, 30)));
        add(startGameButton);
        add(Box.createRigidArea(new Dimension(0, 15)));
        add(raceButton);
        add(Box.createRigidArea(new Dimension(0, 15)));
        add(leaderboardButton);
        add(Box.createRigidArea(new Dimension(0, 15)));
        add(logoutButton);

        // Info real-time dari server (PUSH_ONLINE dan PUSH_INFO)
        onlineLabel.setFont(Theme.FONT_SMALL);
        onlineLabel.setForeground(Theme.COLOR_CORRECT.brighter());
        onlineLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        activityLabel.setFont(Theme.FONT_SMALL);
        activityLabel.setForeground(Theme.FG_TEXT_SOFT);
        activityLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        add(Box.createRigidArea(new Dimension(0, 40)));
        add(onlineLabel);
        add(Box.createRigidArea(new Dimension(0, 8)));
        add(activityLabel);
    }

    public void setOnlineCount(String count) {
        onlineLabel.setText("● " + count + " pemain online");
    }

    public void setActivity(String text) {
        activityLabel.setText(text);
    }

    private JButton createStyledButton(String text) {
        JButton btn = new JButton(text);
        btn.setAlignmentX(Component.CENTER_ALIGNMENT);
        btn.setFont(Theme.FONT_NORMAL);
        btn.setPreferredSize(new Dimension(250, 38));
        btn.setMaximumSize(new Dimension(250, 38));
        btn.setBackground(Theme.BTN_COLOR);
        btn.setForeground(Theme.BTN_TEXT);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);

        btn.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent evt) {
                btn.setBackground(Theme.BTN_TEXT);
                btn.setForeground(Theme.BTN_COLOR);
            }

            public void mouseExited(java.awt.event.MouseEvent evt) {
                btn.setBackground(Theme.BTN_COLOR);
                btn.setForeground(Theme.BTN_TEXT);
            }
        });

        return btn;
    }

    public void setWelcomeMessage(String usn) {
        welcomeLabel.setText("Selamat datang, " + usn);
    }
}