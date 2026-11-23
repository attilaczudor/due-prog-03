package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.Role;
import hu.due.rewear.model.StockMovement;
import hu.due.rewear.model.User;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Administrator-only console providing:
 * 1) User & Role Management (create, edit, password reset, activate/deactivate)
 * 2) Full Stock Movement Audit Log viewer + CSV export
 * 3) Database Backup / Restore and External CSV Translations Export & Live Reload
 */
public final class AdminView extends JPanel implements NavigableView {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final AppContext ctx;

    private final JTabbedPane tabs = new JTabbedPane();

    // Tab 1: Users
    private final JButton addUserBtn = new JButton();
    private final JButton editUserBtn = new JButton();
    private final JButton resetPwBtn = new JButton();
    private final DefaultTableModel usersModel = BrutalistStyle.nonEditableTableModel();
    private final JTable usersTable = new JTable(usersModel);

    // Tab 2: Audit Log
    private final JButton exportAuditBtn = new JButton();
    private final DefaultTableModel auditModel = BrutalistStyle.nonEditableTableModel();
    private final JTable auditTable = new JTable(auditModel);

    // Tab 3: Maintenance
    private final JPanel dbCard = new JPanel();
    private final JLabel dbCardTitle = new JLabel();
    private final JLabel dbInfoLabel = new JLabel();
    private final JButton backupDbBtn = new JButton();
    private final JButton restoreDbBtn = new JButton();

    private final JPanel i18nCard = new JPanel();
    private final JLabel i18nCardTitle = new JLabel();
    private final JLabel i18nInfoLabel = new JLabel();
    private final JButton exportI18nBtn = new JButton();
    private final JButton loadI18nBtn = new JButton();
    private final JButton resetI18nBtn = new JButton();

    // Tab 4: System & Packages
    private final JPanel runtimeCard = new JPanel();
    private final JLabel runtimeCardTitle = new JLabel();
    private final JLabel runtimeInfoLabel = new JLabel();
    private final JLabel packagesTitle = new JLabel();
    private final DefaultTableModel packagesModel = BrutalistStyle.nonEditableTableModel();
    private final JTable packagesTable = new JTable(packagesModel);

    // Tab 5: Email Settings (SUPER_ADMIN only)
    private AdminEmailSettingsPanel emailSettingsPanel;

    // Tab 6: Roles & Modules Access Matrix (SUPER_ADMIN only)
    private AdminModulesPanel modulesPanel;

    private List<User> users = List.of();
    private List<StockMovement> allMovements = List.of();

    public AdminView(AppContext ctx) {
        this.ctx = ctx;

        setLayout(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        buildUi();
        wireEvents();
        applyTranslations();
        applyTheme();
    }

    private void buildUi() {
        // Tab 1: Users
        JPanel usersPanel = new JPanel(new BorderLayout(0, 8));
        usersPanel.setBorder(BorderFactory.createEmptyBorder(10, 4, 4, 4));
        JPanel usersBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        usersBar.add(addUserBtn);
        usersBar.add(editUserBtn);
        usersBar.add(resetPwBtn);
        usersPanel.add(usersBar, BorderLayout.NORTH);
        BrutalistStyle.styleTable(usersTable);
        usersTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        usersPanel.add(new JScrollPane(usersTable), BorderLayout.CENTER);

        // Tab 2: Audit Log
        JPanel auditPanel = new JPanel(new BorderLayout(0, 8));
        auditPanel.setBorder(BorderFactory.createEmptyBorder(10, 4, 4, 4));
        JPanel auditBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        auditBar.add(exportAuditBtn);
        auditPanel.add(auditBar, BorderLayout.NORTH);
        BrutalistStyle.styleTable(auditTable);
        auditTable.setAutoCreateRowSorter(true);
        auditPanel.add(new JScrollPane(auditTable), BorderLayout.CENTER);

        // Tab 3: Maintenance
        JPanel maintPanel = new JPanel();
        maintPanel.setLayout(new BoxLayout(maintPanel, BoxLayout.Y_AXIS));
        maintPanel.setBorder(BorderFactory.createEmptyBorder(12, 4, 4, 4));

        dbCard.setLayout(new BoxLayout(dbCard, BoxLayout.Y_AXIS));
        dbCard.setAlignmentX(LEFT_ALIGNMENT);
        dbCardTitle.setFont(ThemeManager.monoFont(Font.BOLD, 14f));
        dbInfoLabel.setFont(ThemeManager.monoFont(Font.PLAIN, 12f));
        JPanel dbBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        dbBtns.setAlignmentX(LEFT_ALIGNMENT);
        dbBtns.add(backupDbBtn);
        dbBtns.add(restoreDbBtn);
        dbCard.add(dbCardTitle);
        dbCard.add(Box.createVerticalStrut(6));
        dbCard.add(dbInfoLabel);
        dbCard.add(Box.createVerticalStrut(8));
        dbCard.add(dbBtns);

        i18nCard.setLayout(new BoxLayout(i18nCard, BoxLayout.Y_AXIS));
        i18nCard.setAlignmentX(LEFT_ALIGNMENT);
        i18nCardTitle.setFont(ThemeManager.monoFont(Font.BOLD, 14f));
        i18nInfoLabel.setFont(ThemeManager.monoFont(Font.PLAIN, 12f));
        JPanel i18nBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        i18nBtns.setAlignmentX(LEFT_ALIGNMENT);
        i18nBtns.add(exportI18nBtn);
        i18nBtns.add(loadI18nBtn);
        i18nBtns.add(resetI18nBtn);
        i18nCard.add(i18nCardTitle);
        i18nCard.add(Box.createVerticalStrut(6));
        i18nCard.add(i18nInfoLabel);
        i18nCard.add(Box.createVerticalStrut(8));
        i18nCard.add(i18nBtns);

        maintPanel.add(dbCard);
        maintPanel.add(Box.createVerticalStrut(14));
        maintPanel.add(i18nCard);
        maintPanel.add(Box.createVerticalGlue());

        // Tab 4: System & Packages
        JPanel systemPanel = new JPanel(new BorderLayout(0, 12));
        systemPanel.setBorder(BorderFactory.createEmptyBorder(12, 4, 4, 4));

        runtimeCard.setLayout(new BoxLayout(runtimeCard, BoxLayout.Y_AXIS));
        runtimeCardTitle.setFont(ThemeManager.monoFont(Font.BOLD, 13.5f));
        runtimeInfoLabel.setFont(ThemeManager.monoFont(Font.PLAIN, 12f));
        runtimeCard.add(runtimeCardTitle);
        runtimeCard.add(Box.createVerticalStrut(6));
        runtimeCard.add(runtimeInfoLabel);
        systemPanel.add(runtimeCard, BorderLayout.NORTH);

        JPanel pkgWrap = new JPanel(new BorderLayout(0, 6));
        packagesTitle.setFont(ThemeManager.monoFont(Font.BOLD, 13f));
        pkgWrap.add(packagesTitle, BorderLayout.NORTH);
        BrutalistStyle.styleTable(packagesTable);
        pkgWrap.add(new JScrollPane(packagesTable), BorderLayout.CENTER);
        systemPanel.add(pkgWrap, BorderLayout.CENTER);

        tabs.addTab("", usersPanel);
        tabs.addTab("", auditPanel);
        tabs.addTab("", maintPanel);
        tabs.addTab("", systemPanel);
        if (ctx.getSession().isSuperAdmin()) {
            emailSettingsPanel = new AdminEmailSettingsPanel(ctx, this::refreshData);
            tabs.addTab("", emailSettingsPanel);
            modulesPanel = new AdminModulesPanel(ctx);
            tabs.addTab("", modulesPanel);
        }
        add(tabs, BorderLayout.CENTER);
    }

    private void wireEvents() {
        addUserBtn.addActionListener(e -> openCreateUserDialog());
        editUserBtn.addActionListener(e -> openEditUserDialog());
        resetPwBtn.addActionListener(e -> handleResetPassword());

        exportAuditBtn.addActionListener(e -> handleExportAuditCsv());

        backupDbBtn.addActionListener(e -> handleBackupDb());
        restoreDbBtn.addActionListener(e -> handleRestoreDb());

        exportI18nBtn.addActionListener(e -> handleExportTranslations());
        loadI18nBtn.addActionListener(e -> handleLoadTranslations());
        resetI18nBtn.addActionListener(e -> handleResetTranslations());
    }

    private User getSelectedUser() {
        int r = usersTable.getSelectedRow();
        return (r >= 0 && r < users.size()) ? users.get(r) : null;
    }

    private void openCreateUserDialog() {
        I18nManager i18n = ctx.getI18n();
        List<Role> roles = ctx.getRoleDao().findAll().stream()
                .filter(r -> r.code() != hu.due.rewear.model.RoleCode.SUPER_ADMIN)
                .toList();
        JTextField userF = new JTextField(18);
        JTextField lastNameF = new JTextField(18);
        JTextField firstNameF = new JTextField(18);
        JTextField emailF = new JTextField(22);
        emailF.setEditable(ctx.getSession().isSuperAdmin());
        Runnable updateEmailPreview = () -> {
            String gen = ctx.getEmailService().generateDefaultEmail(
                    firstNameF.getText(), lastNameF.getText(), userF.getText());
            emailF.setText(gen);
        };
        javax.swing.event.DocumentListener nameListener = new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { updateEmailPreview.run(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { updateEmailPreview.run(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { updateEmailPreview.run(); }
        };
        lastNameF.getDocument().addDocumentListener(nameListener);
        firstNameF.getDocument().addDocumentListener(nameListener);
        userF.getDocument().addDocumentListener(nameListener);
        updateEmailPreview.run();

        JPasswordField pwF = new JPasswordField(18);
        JComboBox<Role> roleCombo = new JComboBox<>(roles.toArray(new Role[0]));
        JComboBox<StationOption> stationCombo = buildStationCombo(null, i18n);
        Runnable syncStationEnabled = () -> {
            Role selRole = (Role) roleCombo.getSelectedItem();
            stationCombo.setEnabled(selRole != null && selRole.code() == hu.due.rewear.model.RoleCode.OPERATOR);
        };
        roleCombo.addActionListener(e -> syncStationEnabled.run());
        syncStationEnabled.run();

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int r = 0;
        addField(form, gbc, r++, i18n.t("login.username") + " *", userF);
        addField(form, gbc, r++, i18n.t("profile.last_name") + " *", lastNameF);
        addField(form, gbc, r++, i18n.t("profile.first_name") + " *", firstNameF);
        addField(form, gbc, r++, i18n.t("customer.email"), emailF);
        addField(form, gbc, r++, i18n.t("login.password") + " *", pwF);
        addField(form, gbc, r++, i18n.t("admin.user.role") + " *", roleCombo);
        addField(form, gbc, r, i18n.t("admin.user.assigned_station"), stationCombo);

        if (JOptionPane.showConfirmDialog(this, form, i18n.t("common.add"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        Role role = (Role) roleCombo.getSelectedItem();
        if (role == null) return;
        String combinedName = (lastNameF.getText().trim() + " " + firstNameF.getText().trim()).trim();
        try {
            long createdId = ctx.getAuthService().createUser(userF.getText(), new String(pwF.getPassword()), combinedName, role.id(), true);
            String finalEmail = emailF.getText().trim().isEmpty()
                    ? ctx.getEmailService().generateDefaultEmail(firstNameF.getText(), lastNameF.getText(), userF.getText())
                    : emailF.getText().trim();
            ctx.getUserDao().updateEmail(createdId, finalEmail);
            if (role.code() == hu.due.rewear.model.RoleCode.OPERATOR
                    && stationCombo.getSelectedItem() instanceof StationOption so) {
                ctx.getUserDao().updateAssignedStation(createdId, so.stationId());
            }
            refreshData();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openEditUserDialog() {
        User u = getSelectedUser();
        if (u == null) return;
        I18nManager i18n = ctx.getI18n();
        boolean targetIsSuperAdmin = u.roleCode() == hu.due.rewear.model.RoleCode.SUPER_ADMIN;
        List<Role> roles = ctx.getRoleDao().findAll().stream()
                .filter(role -> targetIsSuperAdmin || role.code() != hu.due.rewear.model.RoleCode.SUPER_ADMIN)
                .toList();

        JTextField lastNameF = new JTextField(u.safeLastName(), 18);
        JTextField firstNameF = new JTextField(u.safeFirstName(), 18);
        String initialEmail = (u.email() != null && !u.email().isBlank())
                ? u.email()
                : ctx.getEmailService().generateDefaultEmail(u.safeFirstName(), u.safeLastName(), u.username());
        JTextField emailF = new JTextField(initialEmail, 22);
        emailF.setEditable(ctx.getSession().isSuperAdmin());
        JComboBox<Role> roleCombo = new JComboBox<>(roles.toArray(new Role[0]));
        for (Role role : roles) {
            if (role.id() == u.roleId()) {
                roleCombo.setSelectedItem(role);
                break;
            }
        }
        JComboBox<StationOption> stationCombo = buildStationCombo(u.assignedStationId(), i18n);
        Runnable syncStationEnabled = () -> {
            Role selRole = (Role) roleCombo.getSelectedItem();
            stationCombo.setEnabled(selRole != null && selRole.code() == hu.due.rewear.model.RoleCode.OPERATOR);
        };
        roleCombo.addActionListener(e -> syncStationEnabled.run());
        syncStationEnabled.run();

        JCheckBox activeBox = new JCheckBox(i18n.t("common.activate"), u.active());
        if (targetIsSuperAdmin) {
            roleCombo.setEnabled(false);
            activeBox.setEnabled(false);
        }

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int r = 0;
        addField(form, gbc, r++, i18n.t("profile.last_name") + " *", lastNameF);
        addField(form, gbc, r++, i18n.t("profile.first_name") + " *", firstNameF);
        addField(form, gbc, r++, i18n.t("customer.email"), emailF);
        addField(form, gbc, r++, i18n.t("admin.user.role") + " *", roleCombo);
        addField(form, gbc, r++, i18n.t("admin.user.assigned_station"), stationCombo);
        gbc.gridx = 1; gbc.gridy = r; form.add(activeBox, gbc);

        if (JOptionPane.showConfirmDialog(this, form, i18n.t("common.edit") + " — " + u.username(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        long currentAdminId = ctx.getSession().requireCurrentUser().id();
        if (u.id() == currentAdminId && !activeBox.isSelected()) {
            JOptionPane.showMessageDialog(this, i18n.t("admin.user.cannot_disable_self"),
                    i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        Role role = (Role) roleCombo.getSelectedItem();
        if (role == null) return;
        try {
            ctx.getUserDao().updateProfile(u.id(), lastNameF.getText(), firstNameF.getText(), role.id(), activeBox.isSelected());
            if (ctx.getSession().isSuperAdmin()) {
                ctx.getUserDao().updateEmail(u.id(), emailF.getText().trim());
            }
            Long assignedId = (role.code() == hu.due.rewear.model.RoleCode.OPERATOR
                    && stationCombo.getSelectedItem() instanceof StationOption so)
                    ? so.stationId() : null;
            ctx.getUserDao().updateAssignedStation(u.id(), assignedId);
            refreshData();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private record StationOption(Long stationId, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private JComboBox<StationOption> buildStationCombo(Long selectedStationId, I18nManager i18n) {
        JComboBox<StationOption> combo = new JComboBox<>();
        StationOption none = new StationOption(null, "— " + i18n.t("common.all") + " —");
        combo.addItem(none);
        StationOption selected = none;
        for (var loc : ctx.getLocationDao().findByType(hu.due.rewear.model.LocationType.OPERATOR_STATION, true)) {
            StationOption opt = new StationOption(loc.id(), loc.code() + " (" + loc.zone() + "-" + loc.aisle() + ")");
            combo.addItem(opt);
            if (selectedStationId != null && selectedStationId.equals(loc.id())) {
                selected = opt;
            }
        }
        combo.setSelectedItem(selected);
        return combo;
    }

    private void handleResetPassword() {
        User u = getSelectedUser();
        if (u == null) return;
        I18nManager i18n = ctx.getI18n();
        String newPw = JOptionPane.showInputDialog(this, i18n.t("admin.user.new_pw_prompt"),
                i18n.t("admin.user.reset_pw") + " (" + u.username() + ")", JOptionPane.PLAIN_MESSAGE);
        if (newPw == null) return;
        try {
            ctx.getAuthService().resetPassword(u.id(), newPw);
            JOptionPane.showMessageDialog(this, i18n.t("common.success"),
                    i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleExportAuditCsv() {
        I18nManager i18n = ctx.getI18n();
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("CSV Files (*.csv)", "csv"));
        chooser.setSelectedFile(new File("rewear-audit-log.csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        Path target = file.getName().toLowerCase().endsWith(".csv") ? file.toPath() : Path.of(file.getAbsolutePath() + ".csv");
        try {
            ctx.getCsvExportService().exportStockMovements(allMovements, target);
            JOptionPane.showMessageDialog(this, i18n.t("print.exported", target.toAbsolutePath()),
                    i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleBackupDb() {
        I18nManager i18n = ctx.getI18n();
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("SQLite Database (*.db)", "db"));
        chooser.setSelectedFile(new File("rewear-backup-" + FILE_TS.format(LocalDateTime.now()) + ".db"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        Path target = file.getName().toLowerCase().endsWith(".db") ? file.toPath() : Path.of(file.getAbsolutePath() + ".db");
        try {
            ctx.getBackupService().backupDatabase(target);
            JOptionPane.showMessageDialog(this, i18n.t("print.exported", target.toAbsolutePath()),
                    i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleRestoreDb() {
        I18nManager i18n = ctx.getI18n();
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("SQLite Database (*.db)", "db"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        if (JOptionPane.showConfirmDialog(this, i18n.t("admin.db.restore_confirm"),
                i18n.t("common.confirm"), JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            ctx.getBackupService().restoreDatabase(chooser.getSelectedFile().toPath());
            refreshData();
            JOptionPane.showMessageDialog(this, i18n.t("admin.db.restored"),
                    i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleExportTranslations() {
        I18nManager i18n = ctx.getI18n();
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("CSV Files (*.csv)", "csv"));
        chooser.setSelectedFile(new File("translations.csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        Path target = file.getName().toLowerCase().endsWith(".csv") ? file.toPath() : Path.of(file.getAbsolutePath() + ".csv");
        try {
            i18n.exportToFile(target);
            JOptionPane.showMessageDialog(this, i18n.t("print.exported", target.toAbsolutePath()),
                    i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleLoadTranslations() {
        I18nManager i18n = ctx.getI18n();
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("CSV Files (*.csv)", "csv"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path csvPath = chooser.getSelectedFile().toPath();
        try {
            i18n.loadFromFile(csvPath);
            ctx.getPreferences().setExternalTranslationsPath(csvPath.toAbsolutePath().toString());
            updateMaintenanceInfo();
            JOptionPane.showMessageDialog(this, i18n.t("admin.i18n.loaded", i18n.getAvailableLanguages()),
                    i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleResetTranslations() {
        ctx.getPreferences().setExternalTranslationsPath("");
        ctx.getI18n().loadBundledDefault();
        ctx.getI18n().setLanguage(ctx.getPreferences().getLanguage());
        updateMaintenanceInfo();
        updateSystemDiagnostics();
        if (emailSettingsPanel != null) {
            emailSettingsPanel.refreshData();
        }
        if (modulesPanel != null) {
            modulesPanel.refreshData();
        }
    }

    private void updateMaintenanceInfo() {
        Path dbPath = ctx.getDatabaseManager().getDatabasePath();
        long kb = 0;
        try {
            if (Files.exists(dbPath)) {
                kb = Math.max(1, Files.size(dbPath) / 1024);
            }
        } catch (Exception ignored) {
        }
        dbInfoLabel.setText("FILE: " + dbPath + "  (" + kb + " KB)");

        List<String> langs = ctx.getI18n().getAvailableLanguages().stream()
                .map(s -> s.toUpperCase(Locale.ROOT)).toList();
        i18nInfoLabel.setText(ctx.getI18n().t("admin.i18n.loaded", String.join(", ", langs)));
    }

    private static void addField(JPanel form, GridBagConstraints gbc, int row, String label, JComponent field) {
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        form.add(new JLabel(label), gbc);
        gbc.gridx = 1; gbc.weightx = 1;
        form.add(field, gbc);
    }

    @Override
    public String getViewKey() {
        return MainFrame.VIEW_ADMIN;
    }

    @Override
    public String getTitleI18nKey() {
        return "admin.title";
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    @Override
    public void refreshData() {
        I18nManager i18n = ctx.getI18n();
        String yes = i18n.t("common.yes").toUpperCase();
        String no = i18n.t("common.no").toUpperCase();

        users = ctx.getUserDao().findAll();
        usersModel.setRowCount(0);
        for (User u : users) {
            usersModel.addRow(new Object[]{
                    u.username(),
                    u.fullName(),
                    u.email() != null ? u.email() : "",
                    i18n.t("role." + u.roleCode().name()).toUpperCase(),
                    u.active() ? yes : no,
                    u.createdAt() != null ? DT_FMT.format(u.createdAt()) : ""
            });
        }

        allMovements = ctx.getMovementDao().findAll();
        auditModel.setRowCount(0);
        for (StockMovement m : allMovements) {
            String fromTo = (m.fromLocationCode() != null ? m.fromLocationCode() : "—")
                    + " -> " + (m.toLocationCode() != null ? m.toLocationCode() : "—");
            auditModel.addRow(new Object[]{
                    m.id(),
                    m.createdAt() != null ? DT_FMT.format(m.createdAt()) : "",
                    m.packageQrCode(),
                    i18n.t("movement." + m.movementType().name()).toUpperCase(),
                    fromTo,
                    m.userFullName() + " (" + m.username() + ")"
            });
        }

        updateMaintenanceInfo();
        updateSystemDiagnostics();
        if (emailSettingsPanel != null) {
            emailSettingsPanel.refreshData();
        }
        if (modulesPanel != null) {
            modulesPanel.refreshData();
        }
    }

    private void updateSystemDiagnostics() {
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        Path dbPath = ctx.getDatabaseManager().getDatabasePath();
        long dbKb = 0;
        try {
            if (java.nio.file.Files.exists(dbPath)) {
                dbKb = java.nio.file.Files.size(dbPath) / 1024;
            }
        } catch (Exception ignored) {
            // Ignore transient size read error
        }
        long receiptCount = ctx.getReceiptVaultService().countVaultFiles();
        String theme = ThemeManager.isDarkMode() ? "DARK" : "LIGHT";

        runtimeInfoLabel.setText("<html>"
                + "<b>Java:</b> " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")"
                + " &nbsp;|&nbsp; <b>OS:</b> " + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + " &nbsp;|&nbsp; <b>Heap:</b> " + usedMb + " / " + maxMb + " MB<br>"
                + "<b>SQLite DB:</b> " + dbPath + " (" + dbKb + " KB)"
                + " &nbsp;|&nbsp; <b>Receipts Vault:</b> " + ctx.getReceiptVaultService().getReceiptsDir()
                + " (" + receiptCount + " file(s))<br>"
                + "<b>UI:</b> Brutalist Monochrome (" + theme + ") &nbsp;|&nbsp; <b>Active Language:</b> "
                + ctx.getI18n().getActiveLanguage().toUpperCase(java.util.Locale.ROOT)
                + "</html>");

        packagesModel.setRowCount(0);
        Object[][] deps = {
                {"Java Runtime", System.getProperty("java.version"), "Desktop JVM (Target: Java 17)"},
                {"FlatLaf", "3.5.4", "Brutalist Monochrome Light/Dark Swing Look & Feel"},
                {"SQLite JDBC (xerial)", "3.47.1.0", "Embedded Zero-Server 3NF Relational Database"},
                {"ZXing Core + JavaSE", "3.5.3", "QR Code Encoding & Image/Camera Decoding"},
                {"OpenPDF (LibrePDF)", "2.0.3", "A4 Invoices, Delivery Notes, Statements & Label PDFs"},
                {"XChart", "3.8.8", "Dashboard Monochrome Pie & Bar Analytics"},
                {"jBCrypt", "0.4", "Salted Password Hashing & Authentication"},
                {"SLF4J + Logback", "2.0.16 / 1.5.12", "Structured Application & Audit Logging"}
        };
        for (Object[] row : deps) {
            packagesModel.addRow(row);
        }
    }

    @Override
    public void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        tabs.setTitleAt(0, i18n.t("admin.tab.users").toUpperCase());
        tabs.setTitleAt(1, i18n.t("admin.tab.audit").toUpperCase());
        tabs.setTitleAt(2, i18n.t("admin.tab.maintenance").toUpperCase());
        tabs.setTitleAt(3, i18n.t("admin.tab.system").toUpperCase());
        if (emailSettingsPanel != null && tabs.getTabCount() > 4) {
            tabs.setTitleAt(4, i18n.t("admin.tab.mail").toUpperCase());
            emailSettingsPanel.applyTranslations();
        }
        if (modulesPanel != null && tabs.getTabCount() > 5) {
            tabs.setTitleAt(5, i18n.t("admin.tab.modules").toUpperCase());
            modulesPanel.applyTranslations();
        }

        runtimeCardTitle.setText(i18n.t("admin.system.runtime").toUpperCase());
        packagesTitle.setText(i18n.t("admin.system.packages").toUpperCase());
        packagesModel.setColumnIdentifiers(new Object[]{
                i18n.t("admin.system.component").toUpperCase(),
                i18n.t("admin.system.version").toUpperCase(),
                i18n.t("admin.system.purpose").toUpperCase()
        });

        addUserBtn.setText("+ " + i18n.t("common.add").toUpperCase());
        editUserBtn.setText(i18n.t("common.edit").toUpperCase());
        resetPwBtn.setText(i18n.t("admin.user.reset_pw").toUpperCase());

        exportAuditBtn.setText(i18n.t("common.export.csv").toUpperCase());

        dbCardTitle.setText("SQLITE DATABASE");
        backupDbBtn.setText(i18n.t("admin.db.backup").toUpperCase());
        restoreDbBtn.setText(i18n.t("admin.db.restore").toUpperCase());

        i18nCardTitle.setText("CSV INTERNATIONALIZATION (I18N)");
        exportI18nBtn.setText(i18n.t("admin.i18n.export").toUpperCase());
        loadI18nBtn.setText(i18n.t("admin.i18n.reload").toUpperCase());
        resetI18nBtn.setText(i18n.t("admin.i18n.reset").toUpperCase());

        usersModel.setColumnIdentifiers(new Object[]{
                i18n.t("login.username").toUpperCase(),
                i18n.t("admin.user.full_name").toUpperCase(),
                i18n.t("customer.email").toUpperCase(),
                i18n.t("admin.user.role").toUpperCase(),
                i18n.t("common.status").toUpperCase(),
                i18n.t("common.created_at").toUpperCase()
        });
        auditModel.setColumnIdentifiers(new Object[]{
                "ID",
                i18n.t("common.created_at").toUpperCase(),
                i18n.t("intake.generated_code").toUpperCase(),
                i18n.t("common.actions").toUpperCase(),
                i18n.t("intake.location").toUpperCase(),
                i18n.t("scanner.field.operator").toUpperCase()
        });

        refreshData();
    }

    @Override
    public void applyTheme() {
        BrutalistStyle.stylePrimaryButton(addUserBtn);
        BrutalistStyle.stylePrimaryButton(backupDbBtn);
        BrutalistStyle.stylePrimaryButton(loadI18nBtn);
        BrutalistStyle.styleSecondaryButtons(
                editUserBtn, resetPwBtn,
                exportAuditBtn,
                restoreDbBtn, exportI18nBtn, resetI18nBtn);
        BrutalistStyle.applyBoxCard(dbCard, 14, 16);
        BrutalistStyle.applyBoxCard(i18nCard, 14, 16);
        BrutalistStyle.applyBoxCard(runtimeCard, 14, 16);
        BrutalistStyle.styleTable(usersTable);
        BrutalistStyle.styleTable(auditTable);
        BrutalistStyle.styleTable(packagesTable);
        if (emailSettingsPanel != null) {
            emailSettingsPanel.applyTheme();
        }
        if (modulesPanel != null) {
            modulesPanel.applyTheme();
        }
    }
}
