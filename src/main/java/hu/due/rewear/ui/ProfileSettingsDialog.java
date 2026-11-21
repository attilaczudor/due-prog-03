package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.User;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.io.File;
import java.util.Locale;

/**
 * Brutalist Monochrome Account & Profile Settings dialog opened from the sidebar footer gear button.
 * Allows editing full name, email, phone/contact, uploading/removing a circular profile photo,
 * optionally changing the password, and logging out.
 */
public final class ProfileSettingsDialog extends JDialog {

    private final AppContext ctx;
    private final Runnable onProfileUpdated;
    private final Runnable onLogoutRequested;

    private final AvatarBadge avatarPreview = new AvatarBadge(84);
    private final JLabel usernameLabel = new JLabel();
    private final JLabel roleTagLabel = new JLabel();
    private final JButton uploadPhotoBtn = new JButton();
    private final JButton removePhotoBtn = new JButton();

    private final JTextField fullNameField = new JTextField(24);
    private final JTextField emailField = new JTextField(24);
    private final JTextField phoneField = new JTextField(24);

    private final JPasswordField currentPasswordField = new JPasswordField(20);
    private final JPasswordField newPasswordField = new JPasswordField(20);
    private final JPasswordField confirmPasswordField = new JPasswordField(20);

    private byte[] pendingAvatarPng;

    public ProfileSettingsDialog(Window owner, AppContext ctx,
                                 Runnable onProfileUpdated, Runnable onLogoutRequested) {
        super(owner, ctx.getI18n().t("profile.title"), ModalityType.APPLICATION_MODAL);
        this.ctx = ctx;
        this.onProfileUpdated = onProfileUpdated;
        this.onLogoutRequested = onLogoutRequested;

        User user = ctx.getSession().requireCurrentUser();
        this.pendingAvatarPng = user.avatarPng();

        setMinimumSize(new Dimension(540, 580));
        setPreferredSize(new Dimension(580, 620));

        buildUi(user);
        pack();
        setLocationRelativeTo(owner);
    }

    private void buildUi(User user) {
        I18nManager i18n = ctx.getI18n();

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(ThemeManager.bg());
        root.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));

        // 1. Header Card: Circular Avatar + Username/Role + Upload/Remove Photo
        JPanel avatarCard = new JPanel(new BorderLayout(16, 0));
        BrutalistStyle.applyBoxCard(avatarCard, 12, 14);

        avatarPreview.setUserAppearance(user.initials(), pendingAvatarPng);
        avatarCard.add(avatarPreview, BorderLayout.WEST);

        JPanel identityCol = new JPanel();
        identityCol.setLayout(new BoxLayout(identityCol, BoxLayout.Y_AXIS));
        identityCol.setOpaque(false);

        usernameLabel.setText("@" + user.username());
        usernameLabel.setFont(ThemeManager.monoFont(Font.BOLD, 14f));
        usernameLabel.setForeground(ThemeManager.fg());
        usernameLabel.setAlignmentX(LEFT_ALIGNMENT);

        roleTagLabel.setText(i18n.t("role." + user.roleCode().name()).toUpperCase(Locale.ROOT));
        BrutalistStyle.styleBoxedTag(roleTagLabel, true);
        roleTagLabel.setAlignmentX(LEFT_ALIGNMENT);

        JPanel photoButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        photoButtons.setOpaque(false);
        photoButtons.setAlignmentX(LEFT_ALIGNMENT);

        uploadPhotoBtn.setText(i18n.t("profile.avatar.upload"));
        uploadPhotoBtn.addActionListener(e -> chooseAvatarImage());
        removePhotoBtn.setText(i18n.t("profile.avatar.remove"));
        removePhotoBtn.addActionListener(e -> {
            pendingAvatarPng = null;
            refreshAvatarPreview();
        });
        removePhotoBtn.setEnabled(pendingAvatarPng != null && pendingAvatarPng.length > 0);
        photoButtons.add(uploadPhotoBtn);
        photoButtons.add(removePhotoBtn);

        identityCol.add(usernameLabel);
        identityCol.add(Box.createVerticalStrut(6));
        identityCol.add(roleTagLabel);
        identityCol.add(Box.createVerticalStrut(12));
        identityCol.add(photoButtons);
        avatarCard.add(identityCol, BorderLayout.CENTER);

        root.add(avatarCard, BorderLayout.NORTH);

        // 2. Center: Account Details + Optional Password Change
        JPanel centerStack = new JPanel();
        centerStack.setLayout(new BoxLayout(centerStack, BoxLayout.Y_AXIS));
        centerStack.setOpaque(false);

        JPanel detailsCard = new JPanel(new GridBagLayout());
        BrutalistStyle.applyBoxCard(detailsCard, 12, 14);
        detailsCard.setAlignmentX(LEFT_ALIGNMENT);

        fullNameField.setText(user.fullName() != null ? user.fullName() : "");
        emailField.setText(user.email() != null ? user.email() : "");
        phoneField.setText(user.phone() != null ? user.phone() : "");

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 4, 6, 8);
        gbc.anchor = GridBagConstraints.WEST;

        addFormRow(detailsCard, gbc, 0, i18n.t("profile.fullname"), fullNameField);
        addFormRow(detailsCard, gbc, 1, i18n.t("profile.email"), emailField);
        addFormRow(detailsCard, gbc, 2, i18n.t("profile.phone"), phoneField);

        centerStack.add(detailsCard);
        centerStack.add(Box.createVerticalStrut(12));

        // Optional Password Change Card
        JPanel pwCard = new JPanel(new GridBagLayout());
        BrutalistStyle.applyBoxCard(pwCard, 12, 14);
        pwCard.setAlignmentX(LEFT_ALIGNMENT);

        GridBagConstraints pwGbc = new GridBagConstraints();
        pwGbc.gridx = 0;
        pwGbc.gridy = 0;
        pwGbc.gridwidth = 2;
        pwGbc.anchor = GridBagConstraints.WEST;
        pwGbc.insets = new Insets(0, 4, 8, 4);
        JLabel pwTitle = new JLabel(i18n.t("profile.password.section").toUpperCase(Locale.ROOT));
        pwTitle.setFont(ThemeManager.monoFont(Font.BOLD, 11.5f));
        pwTitle.setForeground(ThemeManager.muted());
        pwCard.add(pwTitle, pwGbc);

        pwGbc.gridwidth = 1;
        pwGbc.insets = new Insets(5, 4, 5, 8);
        addFormRow(pwCard, pwGbc, 1, i18n.t("profile.password.current"), currentPasswordField);
        addFormRow(pwCard, pwGbc, 2, i18n.t("profile.password.new"), newPasswordField);
        addFormRow(pwCard, pwGbc, 3, i18n.t("profile.password.confirm"), confirmPasswordField);

        centerStack.add(pwCard);
        centerStack.add(Box.createVerticalGlue());

        root.add(centerStack, BorderLayout.CENTER);

        // 3. Bottom Bar: Logout on Left | Save & Close on Right
        JPanel bottomBar = new JPanel(new BorderLayout(8, 0));
        bottomBar.setOpaque(false);

        JButton logoutBtn = new JButton(i18n.t("app.logout").toUpperCase(Locale.ROOT));
        logoutBtn.addActionListener(e -> {
            dispose();
            if (onLogoutRequested != null) {
                onLogoutRequested.run();
            }
        });
        JPanel leftActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        leftActions.setOpaque(false);
        leftActions.add(logoutBtn);
        bottomBar.add(leftActions, BorderLayout.WEST);

        JPanel rightActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightActions.setOpaque(false);
        JButton saveBtn = new JButton(i18n.t("common.save"));
        BrutalistStyle.stylePrimaryButton(saveBtn);
        saveBtn.addActionListener(e -> saveProfile());

        JButton closeBtn = new JButton(i18n.t("common.close"));
        closeBtn.addActionListener(e -> dispose());

        rightActions.add(saveBtn);
        rightActions.add(closeBtn);
        bottomBar.add(rightActions, BorderLayout.EAST);

        root.add(bottomBar, BorderLayout.SOUTH);
        setContentPane(root);
    }

    private void addFormRow(JPanel panel, GridBagConstraints gbc, int row, String labelText, JTextField field) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        JLabel lbl = new JLabel(labelText + ":");
        lbl.setForeground(ThemeManager.fg());
        panel.add(lbl, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, gbc);
    }

    private void chooseAvatarImage() {
        I18nManager i18n = ctx.getI18n();
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(i18n.t("profile.avatar.upload"));
        chooser.setFileFilter(new FileNameExtensionFilter("Images (*.png, *.jpg, *.jpeg)", "png", "jpg", "jpeg"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            try {
                pendingAvatarPng = AvatarBadge.encodeAvatarPng(file);
                refreshAvatarPreview();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, i18n.t("profile.error.image") + "\n" + ex.getMessage(),
                        i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void refreshAvatarPreview() {
        User current = ctx.getSession().requireCurrentUser();
        String namePreview = fullNameField.getText().trim();
        User temp = new User(current.id(), current.username(), current.passwordHash(),
                namePreview.isEmpty() ? current.fullName() : namePreview,
                emailField.getText(), phoneField.getText(), pendingAvatarPng,
                current.roleId(), current.roleCode(), current.roleName(), current.active(), current.createdAt());
        avatarPreview.setUserAppearance(temp.initials(), pendingAvatarPng);
        removePhotoBtn.setEnabled(pendingAvatarPng != null && pendingAvatarPng.length > 0);
    }

    private void saveProfile() {
        I18nManager i18n = ctx.getI18n();
        String fullName = fullNameField.getText().trim();
        if (fullName.isEmpty()) {
            JOptionPane.showMessageDialog(this, i18n.t("profile.error.fullname"),
                    i18n.t("common.error"), JOptionPane.WARNING_MESSAGE);
            return;
        }

        String curPw = new String(currentPasswordField.getPassword());
        String newPw = new String(newPasswordField.getPassword());
        String confPw = new String(confirmPasswordField.getPassword());
        boolean wantsPasswordChange = !curPw.isEmpty() || !newPw.isEmpty() || !confPw.isEmpty();

        if (wantsPasswordChange) {
            if (newPw.length() < 6) {
                JOptionPane.showMessageDialog(this, i18n.t("profile.error.password_short"),
                        i18n.t("common.error"), JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (!newPw.equals(confPw)) {
                JOptionPane.showMessageDialog(this, i18n.t("profile.error.password_mismatch"),
                        i18n.t("common.error"), JOptionPane.WARNING_MESSAGE);
                return;
            }
        }

        User user = ctx.getSession().requireCurrentUser();
        try {
            if (wantsPasswordChange) {
                boolean changed = ctx.getAuthService().changePassword(user.id(), curPw, newPw);
                if (!changed) {
                    JOptionPane.showMessageDialog(this, i18n.t("profile.error.password_wrong"),
                            i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }

            ctx.getAuthService().updateOwnProfile(
                    user.id(),
                    fullName,
                    emailField.getText().trim(),
                    phoneField.getText().trim(),
                    pendingAvatarPng
            );

            if (onProfileUpdated != null) {
                onProfileUpdated.run();
            }
            JOptionPane.showMessageDialog(this, i18n.t("profile.saved"),
                    i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
            dispose();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                    i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }
}
