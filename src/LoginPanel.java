import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;

public class LoginPanel extends JPanel {

    private Main mainApp;
    private JTextField usnField = new JTextField(18);
    private JPasswordField passField = new JPasswordField(18);
    private JButton loginButton;
    private JButton registerButton;

    public LoginPanel(Main mainApp) {
        this.mainApp = mainApp;

        setLayout(new GridBagLayout());
        setBackground(Theme.BG_COLOR);

        JPanel container = Theme.createRoundedPanel(25);
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
        container.setBorder(BorderFactory.createEmptyBorder(40, 55, 45, 55)); 

        JLabel title = new JLabel("KataKita");
        title.setFont(Theme.FONT_TITLE.deriveFont(34f));
        title.setForeground(Color.WHITE);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        Theme.styleInput(usnField);
        usnField.setMaximumSize(new Dimension(300, 42)); 
        addPlaceholder(usnField, "Masukkan Username");

        Theme.styleInput(passField);
        passField.setMaximumSize(new Dimension(300, 42));
        passField.setEchoChar((char) 0);
        addPlaceholder(passField, "Masukkan Password");

        loginButton = new JButton("Login");
        Theme.styleButton(loginButton);
        loginButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        loginButton.setMaximumSize(new Dimension(160, 45));

        loginButton.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent evt) { loginButton.setBackground(Theme.BTN_HOVER); }
            public void mouseExited (java.awt.event.MouseEvent evt) { loginButton.setBackground(Theme.BTN_COLOR); }
        });
        loginButton.addActionListener(e -> processLogin());

        registerButton = new JButton("Register");
        Theme.styleButton(registerButton);
        registerButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        registerButton.setMaximumSize(new Dimension(160, 45));

        registerButton.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent evt) { registerButton.setBackground(Theme.BTN_HOVER); }
            public void mouseExited (java.awt.event.MouseEvent evt) { registerButton.setBackground(Theme.BTN_COLOR); }
        });
        registerButton.addActionListener(e -> processRegister());


        container.add(title);
        container.add(Box.createRigidArea(new Dimension(0, 35)));

        container.add(usnField);
        container.add(Box.createRigidArea(new Dimension(0, 18)));

        container.add(passField);
        container.add(Box.createRigidArea(new Dimension(0, 28)));

        container.add(loginButton);
        container.add(Box.createRigidArea(new Dimension(0, 14)));

        container.add(registerButton);

        add(container, new GridBagConstraints());
    }


    private void addPlaceholder(JTextComponent field, String text) {
        field.setForeground(new Color(170, 170, 170));
        field.setText(text);

        field.addFocusListener(new FocusAdapter() {
            boolean isPassword = field instanceof JPasswordField;

            @Override
            public void focusGained(FocusEvent e) {
                if (field.getText().equals(text)) {
                    field.setText("");
                    field.setForeground(Color.WHITE);

                    if (isPassword) {
                        ((JPasswordField) field).setEchoChar('•');
                    }
                }
            }

            @Override
            public void focusLost(FocusEvent e) {
                if (field.getText().trim().isEmpty()) {
                    field.setForeground(new Color(170, 170, 170));
                    field.setText(text);

                    if (isPassword) {
                        ((JPasswordField) field).setEchoChar((char) 0);
                    }
                }
            }
        });
    }

    private void processLogin() {
        String username = usnField.getText().trim();
        String password = new String(passField.getPassword());

        if (username.isEmpty() || username.equals("Masukkan Username")
                || password.isEmpty() || password.equals("Masukkan Password")) {

            JOptionPane.showMessageDialog(this, "Isi username dan password terlebih dahulu.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        setButtonsEnabled(false);
        mainApp.getConnection().requestAsync(r -> {
            setButtonsEnabled(true);
            if (Protocol.OK.equals(r[0])) {
                resetField();
                JOptionPane.showMessageDialog(this, "Login berhasil.", "Message", JOptionPane.INFORMATION_MESSAGE);
                mainApp.onLoginSuccess(Integer.parseInt(r[1]), r[2]);
            } else {
                JOptionPane.showMessageDialog(this, r.length > 1 ? r[1] : "Login gagal",
                        "Login Gagal", JOptionPane.ERROR_MESSAGE);
            }
        }, err -> {
            setButtonsEnabled(true);
            JOptionPane.showMessageDialog(this, err, "Error Jaringan", JOptionPane.ERROR_MESSAGE);
        }, Protocol.LOGIN, username, password);
    }

    private void processRegister() {
        String username = usnField.getText().trim();
        String password = new String(passField.getPassword());

        if (username.isEmpty() || username.equals("Masukkan Username")
                || password.isEmpty() || password.equals("Masukkan Password")) {

            JOptionPane.showMessageDialog(this, "Isi username dan password untuk registrasi.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (!username.matches(Protocol.USERNAME_REGEX)) {
            JOptionPane.showMessageDialog(this, "Username 3-20 karakter, hanya huruf, angka, dan _",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        setButtonsEnabled(false);
        mainApp.getConnection().requestAsync(r -> {
            setButtonsEnabled(true);
            if (Protocol.OK.equals(r[0])) {
                JOptionPane.showMessageDialog(this, "Registrasi berhasil! Silakan login.",
                        "Message", JOptionPane.INFORMATION_MESSAGE);
                resetField();
            } else {
                JOptionPane.showMessageDialog(this, r.length > 1 ? r[1] : "Registrasi gagal",
                        "Registrasi Gagal", JOptionPane.ERROR_MESSAGE);
            }
        }, err -> {
            setButtonsEnabled(true);
            JOptionPane.showMessageDialog(this, err, "Error Jaringan", JOptionPane.ERROR_MESSAGE);
        }, Protocol.REGISTER, username, password);
    }

    private void setButtonsEnabled(boolean enabled) {
        loginButton.setEnabled(enabled);
        registerButton.setEnabled(enabled);
    }

    public void resetField() {
        usnField.setText("");
        passField.setText("");
    }
}
