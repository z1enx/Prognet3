import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;

public class LeaderboardPanel extends JPanel {
    private Main mainApp;
    private JTable leaderboardTable;
    private DefaultTableModel modelTable;
    private JLabel updatedLabel = new JLabel(" ");

    public LeaderboardPanel(Main mainApp) {
        this.mainApp = mainApp;

        this.setBackground(Theme.BG_COLOR);
        this.setLayout(new BorderLayout(0, 20));
        this.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JLabel titleLabel = new JLabel(" PERINGKAT TERTINGGI ");
        titleLabel.setFont(Theme.FONT_TITLE);
        titleLabel.setForeground(Theme.BTN_COLOR);
        titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        this.add(titleLabel, BorderLayout.NORTH);

        String[] columnNames = {"Rank", "Username", "Percobaan", "Durasi (dtk)", "Skor Akhir", "Waktu Main"};
        
        modelTable = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        leaderboardTable = new JTable(modelTable);
        leaderboardTable.setBackground(Theme.BG_COLOR);
        leaderboardTable.setRowHeight(35);
        leaderboardTable.setFont(Theme.FONT_NORMAL);
        leaderboardTable.setGridColor(Theme.COLOR_BORDER);
        leaderboardTable.setShowGrid(true);
        leaderboardTable.setFillsViewportHeight(true);
        leaderboardTable.setSelectionBackground(Theme.COLOR_PRESENT);
        leaderboardTable.setSelectionForeground(Theme.FG_TEXT);

        JTableHeader header = leaderboardTable.getTableHeader();
        header.setFont(new Font("SansSerif", Font.BOLD, 14));
        header.setBackground(Theme.BTN_COLOR);
        header.setForeground(Theme.BTN_TEXT);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 2, 0, Theme.COLOR_BORDER));

        DefaultTableCellRenderer darkRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, 
                    boolean isSelected, boolean hasFocus, int row, int column) {
                
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                
                if (!isSelected) {
                    c.setBackground(row % 2 == 0 ? Theme.BG_COLOR : Theme.COLOR_ABSENT);
                    c.setForeground(Theme.FG_TEXT);
                }
                setHorizontalAlignment(SwingConstants.CENTER);
                
                // Highlight Top 3
                if (column == 0 && !isSelected && value != null && !value.toString().equals("—")) {
                    try {
                        int rank = Integer.parseInt(value.toString());
                        if (rank == 1) {
                            c.setForeground(new Color(255, 215, 0)); 
                        } else if (rank == 2) {
                            c.setForeground(new Color(192, 192, 192));
                        } else if (rank == 3) {
                            c.setForeground(new Color(205, 127, 50)); 
                        }
                    } catch (NumberFormatException ex) {
                        
                    }
                }
                
                return c;
            }
        };

        for (int i = 0; i < leaderboardTable.getColumnCount(); i++) {
            leaderboardTable.getColumnModel().getColumn(i).setCellRenderer(darkRenderer);
        }
        
        leaderboardTable.getColumnModel().getColumn(0).setPreferredWidth(50);  
        leaderboardTable.getColumnModel().getColumn(1).setPreferredWidth(150); 
        leaderboardTable.getColumnModel().getColumn(2).setPreferredWidth(100); 
        leaderboardTable.getColumnModel().getColumn(3).setPreferredWidth(100); 
        leaderboardTable.getColumnModel().getColumn(4).setPreferredWidth(100); 
        leaderboardTable.getColumnModel().getColumn(5).setPreferredWidth(180); 

        JScrollPane scrollPane = new JScrollPane(leaderboardTable);
        scrollPane.setBorder(BorderFactory.createLineBorder(Theme.COLOR_BORDER));
        scrollPane.getViewport().setBackground(Theme.BG_COLOR);
        this.add(scrollPane, BorderLayout.CENTER);

        JPanel southPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        southPanel.setBackground(Theme.BG_COLOR);

        JButton backButton = new JButton("« KEMBALI KE MENU");
        Theme.styleButton(backButton); 
        backButton.setBackground(Theme.BTN_COLOR);
        backButton.setForeground(Theme.BTN_TEXT);
        backButton.addActionListener(e -> mainApp.showPanel("MAIN_MENU"));
        
        southPanel.setLayout(new BoxLayout(southPanel, BoxLayout.Y_AXIS));
        updatedLabel.setFont(Theme.FONT_SMALL);
        updatedLabel.setForeground(Theme.FG_TEXT_SOFT);
        updatedLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        backButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        southPanel.add(updatedLabel);
        southPanel.add(Box.createVerticalStrut(10));
        southPanel.add(backButton);
        this.add(southPanel, BorderLayout.SOUTH);
    }
        
    public void onPanelShown() {
        mainApp.getConnection().requestAsync(
                r -> updateFromPayload(r.length > 1 ? r[1] : ""),
                Protocol.LEADERBOARD);
    }

    /**
     * Mengisi tabel dari data leaderboard yang dikirim server.
     * Dipanggil saat panel dibuka (respons LB) dan setiap kali server mem-broadcast
     * PUSH_LB, sehingga tabel ikut berubah tanpa perlu dibuka ulang.
     * Selalu dijalankan di thread UI.
     */
    public void updateFromPayload(String payload) {
        modelTable.setRowCount(0);
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm");

        if (payload == null || payload.isEmpty()) {
            modelTable.addRow(new Object[]{"—", "Belum ada data", "—", "—", "—", "—"});
        } else {
            for (String rowText : payload.split(Protocol.ROW_SEP)) {
                String[] c = rowText.split(Protocol.COL_SEP, -1);
                if (c.length < 6) continue;
                long epoch = Long.parseLong(c[5]);
                modelTable.addRow(new Object[]{
                        c[0],                                   // Rank
                        c[1],                                   // Username
                        c[2] + " kali",                         // Percobaan
                        c[3] + " dtk",                          // Durasi
                        c[4],                                   // Skor
                        epoch > 0 ? sdf.format(new Date(epoch)) : ""  // Waktu Main
                });
            }
        }
        updatedLabel.setText("Update terakhir: " + new SimpleDateFormat("HH:mm:ss").format(new Date())
                + "  (otomatis saat ada pemain selesai)");
    }
}