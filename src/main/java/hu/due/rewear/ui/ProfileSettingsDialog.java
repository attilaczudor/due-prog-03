package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.i18n.LanguageChangeListener;
import hu.due.rewear.model.Role;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.User;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
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
import java.util.List;
import java.util.Locale;

/**
 * Brutalist Monochrome Account & Profile Settings dialog opened from the sidebar footer gear button.
 * Includes circular profile image upload/remove, profile details, live Language & Theme selectors,
 * Role selector with live permissions breakdown, optional password change, and Logout.
 */
public final class ProfileSettingsDialog extends JDialog
        implements LanguageChangeListener, ThemeManager.ThemeChangeListener {

    private final AppContext ctx;
    private final Runnable onProfileUpdated;
    private final Runnable onLogoutRequested;

    private final JPanel root = new JPanel(new BorderLayout(0, 12));
    private final JPanel avatarCard = new JPanel(new BorderLayout(16, 0));
    private final JPanel detailsCard = new JPanel(new GridBagLayout());
    private final JPanel appearanceCard = new JPanel(new GridBagLayout());
    private final JPanel roleCard = new JPanel(new GridBagLayout());
    private final JPanel pwCard = new JPanel(new GridBagLayout());
    private final JPanel permsBox = new JPanel();

    private final AvatarBadge avatarPreview = new AvatarBadge(80);
    private final JLabel usernameLabel = new JLabel();
    private final JLabel roleTagLabel = new JLabel();
    private final JButton uploadPhotoBtn = new JButton();
    private final JButton removePhotoBtn = new JButton();

    private final JLabel handleLbl = new JLabel();
    private final JLabel lastNameLbl = new JLabel();
    private final JLabel firstNameLbl = new JLabel();
    private final JLabel emailLbl = new JLabel();
    private final JLabel phoneLbl = new JLabel();
    private final JTextField handleField = new JTextField(24);
    private final JTextField lastNameField = new JTextField(24);
    private final JTextField firstNameField = new JTextField(24);
    private final JTextField emailField = new JTextField(24);
    private final JTextField phoneField = new JTextField(24);

    // Appearance & Language controls
    private final JLabel appearanceTitleLbl = new JLabel();
    private final JLabel languageLbl = new JLabel();
    private final JComboBox<String> langCombo = new JComboBox<>();
    private final JLabel themeLbl = new JLabel();
    private final JButton lightThemeBtn = new JButton();
    private final JButton darkThemeBtn = new JButton();

    // Role & Permissions controls
    private final JLabel roleTitleLbl = new JLabel();
    private final JLabel roleSelectLbl = new JLabel();
    private final JComboBox<RoleOption> roleCombo = new JComboBox<>();
    private final JLabel roleHintLbl = new JLabel();
    private final JLabel permsHeaderLbl = new JLabel();
    private final JLabel permCoreLbl = new JLabel();
    private final JLabel permCatalogLbl = new JLabel();
    private final JLabel permOrdersLbl = new JLabel();
    private final JLabel permAdminLbl = new JLabel();
    private final JLabel permSuperAdminLbl = new JLabel();

    // Password controls
    private final JLabel pwTitleLbl = new JLabel();
    private final JLabel currentPwLbl = new JLabel();
    private final JLabel newPwLbl = new JLabel();
    private final JLabel confirmPwLbl = new JLabel();
    private final JPasswordField currentPasswordField = new JPasswordField(20);
    private final JPasswordField newPasswordField = new JPasswordField(20);
    private final JPasswordField confirmPasswordField = new JPasswordField(20);

    // Footer buttons (equal size)
    private final JButton logoutBtn = new JButton();
    private final JButton saveBtn = new JButton();
    private final JButton closeBtn = new JButton();

    private final List<Role> allRoles;
    private byte[] pendingAvatarPng;
    private boolean updatingCombos = false;

    private record RoleOption(Role role, String localizedLabel) {
        @Override
        public String toString() {
            return localizedLabel + " (" + role.code().name() + ")";
        }
    }

    public ProfileSettingsDialog(Window owner, AppContext ctx,
                                 Runnable onProfileUpdated, Runnable onLogoutRequested) {
        super(owner, ctx.getI18n().t("profile.title"), ModalityType.APPLICATION_MODAL);
        this.ctx = ctx;
        this.onProfileUpdated = onProfileUpdated;
        this.onLogoutRequested = onLogoutRequested;
        this.allRoles = ctx.getRoleDao().findAll();

        User user = ctx.getSession().requireCurrentUser();
        this.pendingAvatarPng = user.avatarPng();

        setMinimumSize(new Dimension(600, 660));
        setPreferredSize(new Dimension(650, 740));

        buildUi(user);
        populateLanguageCombo();
        populateRoleCombo(user.roleId());
        applyTranslations();
        applyThemeStyles();

        ctx.getI18n().addListener(this);
        ThemeManager.addListener(this);

        BrutalistStyle.installEscapeToClose(this);
        pack();
        setLocationRelativeTo(owner);
    }

    @Override
    public void dispose() {
        ctx.getI18n().removeListener(this);
        ThemeManager.removeListener(this);
        super.dispose();
    }

    private void buildUi(User user) {
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        // 1. Header Card: Circular Avatar + Identity + Upload/Remove Photo
        avatarPreview.setUserAppearance(user.initials(), pendingAvatarPng);
        avatarCard.add(avatarPreview, BorderLayout.WEST);

        JPanel identityCol = new JPanel();
        identityCol.setLayout(new BoxLayout(identityCol, BoxLayout.Y_AXIS));
        identityCol.setOpaque(false);

        usernameLabel.setText("@" + user.username());
        usernameLabel.setFont(ThemeManager.monoFont(Font.BOLD, 14f));
        usernameLabel.setAlignmentX(LEFT_ALIGNMENT);
        roleTagLabel.setAlignmentX(LEFT_ALIGNMENT);

        JPanel photoButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        photoButtons.setOpaque(false);
        photoButtons.setAlignmentX(LEFT_ALIGNMENT);

        uploadPhotoBtn.addActionListener(e -> chooseAvatarImage());
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
        identityCol.add(Box.createVerticalStrut(10));
        identityCol.add(photoButtons);
        avatarCard.add(identityCol, BorderLayout.CENTER);

        // 2. Scrollable stack of settings cards
        JPanel stack = new JPanel();
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        stack.setOpaque(false);

        avatarCard.setAlignmentX(LEFT_ALIGNMENT);
        stack.add(avatarCard);
        stack.add(Box.createVerticalStrut(10));

        // Profile Details Card
        detailsCard.setAlignmentX(LEFT_ALIGNMENT);
        handleField.setText(user.username() != null ? user.username() : "");
        handleField.getDocument().addDocumentListener(new DocumentListener() {
            private void sync() {
                String txt = handleField.getText().trim();
                if (txt.startsWith("@")) {
                    txt = txt.substring(1).trim();
                }
                usernameLabel.setText("@" + (txt.isEmpty() ? user.username() : txt));
            }
            @Override public void insertUpdate(DocumentEvent e) { sync(); }
            @Override public void removeUpdate(DocumentEvent e) { sync(); }
            @Override public void changedUpdate(DocumentEvent e) { sync(); }
        });
        lastNameField.setText(user.safeLastName());
        firstNameField.setText(user.safeFirstName());
        DocumentListener nameSync = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refreshAvatarPreview(); }
            @Override public void removeUpdate(DocumentEvent e) { refreshAvatarPreview(); }
            @Override public void changedUpdate(DocumentEvent e) { refreshAvatarPreview(); }
        };
        lastNameField.getDocument().addDocumentListener(nameSync);
        firstNameField.getDocument().addDocumentListener(nameSync);
        emailField.setText(user.email() != null ? user.email() : "");
        phoneField.setText(user.phone() != null ? user.phone() : "");

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 4, 5, 8);
        gbc.anchor = GridBagConstraints.WEST;
        addFormRow(detailsCard, gbc, 0, handleLbl, handleField);
        addFormRow(detailsCard, gbc, 1, lastNameLbl, lastNameField);
        addFormRow(detailsCard, gbc, 2, firstNameLbl, firstNameField);
        addFormRow(detailsCard, gbc, 3, emailLbl, emailField);
        addFormRow(detailsCard, gbc, 4, phoneLbl, phoneField);
        stack.add(detailsCard);
        stack.add(Box.createVerticalStrut(10));

        // Appearance & Language Card
        appearanceCard.setAlignmentX(LEFT_ALIGNMENT);
        GridBagConstraints appGbc = new GridBagConstraints();
        appGbc.gridx = 0;
        appGbc.gridy = 0;
        appGbc.gridwidth = 2;
        appGbc.anchor = GridBagConstraints.WEST;
        appGbc.insets = new Insets(0, 4, 8, 4);
        appearanceTitleLbl.setFont(ThemeManager.monoFont(Font.BOLD, 11.5f));
        appearanceCard.add(appearanceTitleLbl, appGbc);

        appGbc.gridwidth = 1;
        appGbc.insets = new Insets(5, 4, 5, 8);
        langCombo.addActionListener(e -> {
            if (!updatingCombos && langCombo.getSelectedItem() instanceof String code) {
                String normalized = code.toLowerCase(Locale.ROOT);
                ctx.getI18n().setLanguage(normalized);
                ctx.getPreferences().setLanguage(normalized);
            }
        });
        addFormRow(appearanceCard, appGbc, 1, languageLbl, langCombo);

        JPanel themeTogglePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        themeTogglePanel.setOpaque(false);
        lightThemeBtn.addActionListener(e -> switchTheme(false));
        darkThemeBtn.addActionListener(e -> switchTheme(true));
        themeTogglePanel.add(lightThemeBtn);
        themeTogglePanel.add(darkThemeBtn);
        addFormRow(appearanceCard, appGbc, 2, themeLbl, themeTogglePanel);

        stack.add(appearanceCard);
        stack.add(Box.createVerticalStrut(10));

        // Role & Permissions Card
        roleCard.setAlignmentX(LEFT_ALIGNMENT);
        GridBagConstraints roleGbc = new GridBagConstraints();
        roleGbc.gridx = 0;
        roleGbc.gridy = 0;
        roleGbc.gridwidth = 2;
        roleGbc.anchor = GridBagConstraints.WEST;
        roleGbc.insets = new Insets(0, 4, 8, 4);
        roleTitleLbl.setFont(ThemeManager.monoFont(Font.BOLD, 11.5f));
        roleCard.add(roleTitleLbl, roleGbc);

        roleGbc.gridwidth = 1;
        roleGbc.insets = new Insets(5, 4, 5, 8);
        boolean isSuperAdmin = user.roleCode() == RoleCode.SUPER_ADMIN;
        boolean canEditRole = ctx.getSession().isAdmin() && !isSuperAdmin;
        roleCombo.setEnabled(canEditRole);
        roleCombo.addActionListener(e -> updateRolePermissionsDisplay());
        addFormRow(roleCard, roleGbc, 1, roleSelectLbl, roleCombo);

        if (!canEditRole) {
            roleGbc.gridx = 0;
            roleGbc.gridy = 2;
            roleGbc.gridwidth = 2;
            roleGbc.fill = GridBagConstraints.HORIZONTAL;
            roleGbc.insets = new Insets(0, 4, 6, 4);
            roleHintLbl.setFont(ThemeManager.monoFont(Font.PLAIN, 10.5f));
            roleCard.add(roleHintLbl, roleGbc);
        }

        permsBox.setLayout(new BoxLayout(permsBox, BoxLayout.Y_AXIS));
        permsHeaderLbl.setFont(ThemeManager.monoFont(Font.BOLD, 11f));
        permsHeaderLbl.setAlignmentX(LEFT_ALIGNMENT);
        for (JLabel pLbl : List.of(permCoreLbl, permCatalogLbl, permOrdersLbl, permAdminLbl, permSuperAdminLbl)) {
            pLbl.setFont(ThemeManager.monoFont(Font.PLAIN, 11f));
            pLbl.setAlignmentX(LEFT_ALIGNMENT);
        }
        permsBox.add(permsHeaderLbl);
        permsBox.add(Box.createVerticalStrut(5));
        permsBox.add(permCoreLbl);
        permsBox.add(Box.createVerticalStrut(3));
        permsBox.add(permCatalogLbl);
        permsBox.add(Box.createVerticalStrut(3));
        permsBox.add(permOrdersLbl);
        permsBox.add(Box.createVerticalStrut(3));
        permsBox.add(permAdminLbl);
        permsBox.add(Box.createVerticalStrut(3));
        permsBox.add(permSuperAdminLbl);

        roleGbc.gridx = 0;
        roleGbc.gridy = 3;
        roleGbc.gridwidth = 2;
        roleGbc.weightx = 1.0;
        roleGbc.fill = GridBagConstraints.HORIZONTAL;
        roleGbc.insets = new Insets(6, 4, 2, 4);
        roleCard.add(permsBox, roleGbc);

        stack.add(roleCard);
        stack.add(Box.createVerticalStrut(10));

        // Optional Password Change Card
        pwCard.setAlignmentX(LEFT_ALIGNMENT);
        GridBagConstraints pwGbc = new GridBagConstraints();
        pwGbc.gridx = 0;
        pwGbc.gridy = 0;
        pwGbc.gridwidth = 2;
        pwGbc.anchor = GridBagConstraints.WEST;
        pwGbc.insets = new Insets(0, 4, 8, 4);
        pwTitleLbl.setFont(ThemeManager.monoFont(Font.BOLD, 11.5f));
        pwCard.add(pwTitleLbl, pwGbc);

        pwGbc.gridwidth = 1;
        pwGbc.insets = new Insets(5, 4, 5, 8);
        addFormRow(pwCard, pwGbc, 1, currentPwLbl, currentPasswordField);
        addFormRow(pwCard, pwGbc, 2, newPwLbl, newPasswordField);
        addFormRow(pwCard, pwGbc, 3, confirmPwLbl, confirmPasswordField);
        stack.add(pwCard);

        JScrollPane scroll = new JScrollPane(stack);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        root.add(scroll, BorderLayout.CENTER);

        // 3. Bottom Bar: Equal-size Logout on Left | Save & Close on Right
        JPanel bottomBar = new JPanel(new BorderLayout(8, 0));
        bottomBar.setOpaque(false);

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
        saveBtn.addActionListener(e -> saveProfile());
        closeBtn.addActionListener(e -> dispose());
        rightActions.add(saveBtn);
        rightActions.add(closeBtn);
        bottomBar.add(rightActions, BorderLayout.EAST);

        root.add(bottomBar, BorderLayout.SOUTH);
        setContentPane(root);
    }

    private void addFormRow(JPanel panel, GridBagConstraints gbc, int row, JLabel label, java.awt.Component comp) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(label, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(comp, gbc);
    }

    private void switchTheme(boolean dark) {
        if (ctx.getPreferences().isDarkTheme() != dark) {
            ctx.getPreferences().setDarkTheme(dark);
            ThemeManager.applyTheme(dark);
        }
    }

    private void populateLanguageCombo() {
        updatingCombos = true;
        try {
            langCombo.removeAllItems();
            for (String lang : ctx.getI18n().getAvailableLanguages()) {
                langCombo.addItem(lang.toUpperCase(Locale.ROOT));
            }
            langCombo.setSelectedItem(ctx.getI18n().getActiveLanguage().toUpperCase(Locale.ROOT));
        } finally {
            updatingCombos = false;
        }
    }

    private void populateRoleCombo(long selectedRoleId) {
        updatingCombos = true;
        try {
            I18nManager i18n = ctx.getI18n();
            boolean userIsSuperAdmin = ctx.getSession().isSuperAdmin();
            roleCombo.removeAllItems();
            RoleOption toSelect = null;
            for (Role role : allRoles) {
                // Non-SuperAdmins cannot assign themselves SUPER_ADMIN
                if (role.code() == RoleCode.SUPER_ADMIN && !userIsSuperAdmin) {
                    continue;
                }
                RoleOption opt = new RoleOption(role, i18n.t("role." + role.code().name()));
                roleCombo.addItem(opt);
                if (role.id() == selectedRoleId) {
                    toSelect = opt;
                }
            }
            if (toSelect != null) {
                roleCombo.setSelectedItem(toSelect);
            }
        } finally {
            updatingCombos = false;
        }
        updateRolePermissionsDisplay();
    }

    private void updateRolePermissionsDisplay() {
        I18nManager i18n = ctx.getI18n();
        RoleCode code = ctx.getSession().requireCurrentUser().roleCode();
        if (roleCombo.getSelectedItem() instanceof RoleOption opt) {
            code = opt.role().code();
        }
        boolean isSuper = code == RoleCode.SUPER_ADMIN;
        boolean canCatalog = isSuper || code == RoleCode.ADMIN || code == RoleCode.MANAGER;
        boolean canOrders = isSuper || code == RoleCode.ADMIN || code == RoleCode.MANAGER;
        boolean canAdmin = isSuper || code == RoleCode.ADMIN;

        setPermLine(permCoreLbl, true, i18n.t("profile.perm.core"));
        setPermLine(permCatalogLbl, canCatalog, i18n.t("profile.perm.catalog"));
        setPermLine(permOrdersLbl, canOrders, i18n.t("profile.perm.orders"));
        setPermLine(permAdminLbl, canAdmin, i18n.t("profile.perm.admin"));
        setPermLine(permSuperAdminLbl, isSuper, i18n.t("profile.perm.super_admin"));
    }

    private void setPermLine(JLabel label, boolean allowed, String text) {
        label.setText((allowed ? "[✓] " : "[✗] ") + text);
        label.setForeground(allowed ? ThemeManager.fg() : ThemeManager.muted());
    }

    private void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        User user = ctx.getSession().requireCurrentUser();

        setTitle(i18n.t("profile.title"));
        roleTagLabel.setText(i18n.t("role." + user.roleCode().name()).toUpperCase(Locale.ROOT));
        uploadPhotoBtn.setText(i18n.t("profile.avatar.upload"));
        removePhotoBtn.setText(i18n.t("profile.avatar.remove"));

        handleLbl.setText(i18n.t("profile.handle") + ":");
        lastNameLbl.setText(i18n.t("profile.last_name") + ":");
        firstNameLbl.setText(i18n.t("profile.first_name") + ":");
        emailLbl.setText(i18n.t("profile.email") + ":");
        phoneLbl.setText(i18n.t("profile.phone") + ":");

        appearanceTitleLbl.setText(i18n.t("profile.appearance.section").toUpperCase(Locale.ROOT));
        languageLbl.setText(i18n.t("profile.language") + ":");
        themeLbl.setText(i18n.t("profile.theme") + ":");
        lightThemeBtn.setText(i18n.t("profile.theme.light"));
        darkThemeBtn.setText(i18n.t("profile.theme.dark"));

        roleTitleLbl.setText(i18n.t("profile.role.section").toUpperCase(Locale.ROOT));
        roleSelectLbl.setText(i18n.t("profile.role.label") + ":");
        roleHintLbl.setText(user.roleCode() == RoleCode.SUPER_ADMIN
                ? i18n.t("profile.role.super_admin_locked")
                : i18n.t("profile.role.admin_only"));
        permsHeaderLbl.setText(i18n.t("profile.role.permissions"));

        long selectedRoleId = (roleCombo.getSelectedItem() instanceof RoleOption opt)
                ? opt.role().id() : user.roleId();
        populateRoleCombo(selectedRoleId);

        pwTitleLbl.setText(i18n.t("profile.password.section").toUpperCase(Locale.ROOT));
        currentPwLbl.setText(i18n.t("profile.password.current") + ":");
        newPwLbl.setText(i18n.t("profile.password.new") + ":");
        confirmPwLbl.setText(i18n.t("profile.password.confirm") + ":");

        logoutBtn.setText(i18n.t("app.logout").toUpperCase(Locale.ROOT));
        saveBtn.setText(i18n.t("common.save"));
        closeBtn.setText(i18n.t("common.close"));

        equalizeAllButtonGroups();
    }

    private void applyThemeStyles() {
        root.setBackground(ThemeManager.bg());
        BrutalistStyle.applyBoxCard(avatarCard, 12, 14);
        BrutalistStyle.applyBoxCard(detailsCard, 12, 14);
        BrutalistStyle.applyBoxCard(appearanceCard, 12, 14);
        BrutalistStyle.applyBoxCard(roleCard, 12, 14);
        BrutalistStyle.applyBoxCard(pwCard, 12, 14);

        permsBox.setBackground(ThemeManager.surface());
        permsBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeManager.grid(), 1),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));

        usernameLabel.setForeground(ThemeManager.fg());
        BrutalistStyle.styleBoxedTag(roleTagLabel, true);

        for (JLabel title : List.of(appearanceTitleLbl, roleTitleLbl, pwTitleLbl, roleHintLbl)) {
            title.setForeground(ThemeManager.muted());
        }
        for (JLabel lbl : List.of(handleLbl, lastNameLbl, firstNameLbl, emailLbl, phoneLbl, languageLbl, themeLbl,
                roleSelectLbl, permsHeaderLbl, currentPwLbl, newPwLbl, confirmPwLbl)) {
            lbl.setForeground(ThemeManager.fg());
        }

        boolean dark = ThemeManager.isDarkMode();
        if (dark) {
            BrutalistStyle.styleSecondaryButton(lightThemeBtn);
            BrutalistStyle.stylePrimaryButton(darkThemeBtn);
        } else {
            BrutalistStyle.stylePrimaryButton(lightThemeBtn);
            BrutalistStyle.styleSecondaryButton(darkThemeBtn);
        }

        BrutalistStyle.styleSecondaryButton(uploadPhotoBtn);
        BrutalistStyle.styleSecondaryButton(removePhotoBtn);
        BrutalistStyle.styleSecondaryButton(logoutBtn);
        BrutalistStyle.stylePrimaryButton(saveBtn);
        BrutalistStyle.styleSecondaryButton(closeBtn);

        equalizeAllButtonGroups();
        updateRolePermissionsDisplay();
        avatarPreview.repaint();
        root.revalidate();
        root.repaint();
    }

    private void equalizeAllButtonGroups() {
        BrutalistStyle.matchButtonSizes(uploadPhotoBtn, removePhotoBtn);
        BrutalistStyle.matchButtonSizes(lightThemeBtn, darkThemeBtn);
        BrutalistStyle.matchButtonSizes(logoutBtn, saveBtn, closeBtn);
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
        String ln = lastNameField.getText().trim();
        String fn = firstNameField.getText().trim();
        String full = (ln + " " + fn).trim();
        User temp = new User(current.id(), current.username(), current.passwordHash(),
                full.isEmpty() ? current.fullName() : full,
                ln.isEmpty() ? current.safeLastName() : ln,
                fn.isEmpty() ? current.safeFirstName() : fn,
                emailField.getText(), phoneField.getText(), pendingAvatarPng,
                current.roleId(), current.roleCode(), current.roleName(), current.active(), current.createdAt());
        avatarPreview.setUserAppearance(temp.initials(), pendingAvatarPng);
        removePhotoBtn.setEnabled(pendingAvatarPng != null && pendingAvatarPng.length > 0);
    }

    private void saveProfile() {
        I18nManager i18n = ctx.getI18n();
        String lastName = lastNameField.getText().trim();
        String firstName = firstNameField.getText().trim();
        if (lastName.isEmpty() || firstName.isEmpty()) {
            JOptionPane.showMessageDialog(this, i18n.t("profile.error.name_required"),
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
        Long newRoleId = null;
        if (ctx.getSession().isAdmin() && user.roleCode() != RoleCode.SUPER_ADMIN
                && roleCombo.getSelectedItem() instanceof RoleOption opt) {
            newRoleId = opt.role().id();
        }

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
                    handleField.getText().trim(),
                    lastName,
                    firstName,
                    emailField.getText().trim(),
                    phoneField.getText().trim(),
                    pendingAvatarPng,
                    newRoleId
            );

            if (onProfileUpdated != null) {
                onProfileUpdated.run();
            }
            JOptionPane.showMessageDialog(this, i18n.t("profile.saved"),
                    i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
            dispose();
        } catch (IllegalArgumentException ex) {
            String msg = switch (ex.getMessage()) {
                case "NAME_REQUIRED" -> i18n.t("profile.error.name_required");
                case "INVALID_HANDLE" -> i18n.t("profile.error.handle_invalid");
                case "HANDLE_TAKEN" -> i18n.t("profile.error.handle_taken");
                default -> ex.getMessage();
            };
            JOptionPane.showMessageDialog(this, msg,
                    i18n.t("common.error"), JOptionPane.WARNING_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                    i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    @Override
    public void onLanguageChanged(String languageCode) {
        populateLanguageCombo();
        applyTranslations();
    }

    @Override
    public void onThemeChanged(boolean dark) {
        applyThemeStyles();
    }
}
