package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.SchemaInitializer;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.Role;
import hu.due.rewear.model.RoleCode;
import hu.due.rewear.model.StockMovement;
import hu.due.rewear.model.User;
import hu.due.rewear.util.BusinessClock;

import javax.swing.AbstractAction;
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
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
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

    private final SectionNavigation sections;

    // Users
    private final JButton addUserBtn = new JButton();
    private final DefaultTableModel usersModel = BrutalistStyle.nonEditableTableModel();
    private final JTable usersTable = new JTable(usersModel);

    // Log
    private final JButton exportAuditBtn = new JButton();
    private final DefaultTableModel auditModel = BrutalistStyle.nonEditableTableModel();
    private final JTable auditTable = new JTable(auditModel);

    // Database and translations
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

    // System
    private final JPanel runtimeCard = new JPanel();
    private final JLabel runtimeCardTitle = new JLabel();
    private final JLabel runtimeInfoLabel = new JLabel();
    private final JLabel packagesTitle = new JLabel();
    private final DefaultTableModel packagesModel = BrutalistStyle.nonEditableTableModel();
    private final JTable packagesTable = new JTable(packagesModel);

    // Email Settings (SUPER_ADMIN only)
    private AdminEmailSettingsPanel emailSettingsPanel;

    // Roles (SUPER_ADMIN only)
    private AdminModulesPanel modulesPanel;
    private InvoiceSettingsPanel invoiceSettingsPanel;
    private NavIntegrationPanel navIntegrationPanel;
    private BankIntegrationPanel bankIntegrationPanel;

    private List<User> users = List.of();
    private List<StockMovement> allMovements = List.of();

    public AdminView(AppContext ctx) {
        this.ctx = ctx;
        sections = new SectionNavigation(ctx.getI18n());

        setLayout(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        buildUi();
        wireEvents();
        applyTranslations();
        applyTheme();
    }

    private void buildUi() {
        JPanel usersPanel = new JPanel(new BorderLayout(0, 8));
        usersPanel.setBorder(BorderFactory.createEmptyBorder(10, 4, 4, 4));
        JPanel usersBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        usersBar.add(addUserBtn);
        usersPanel.add(usersBar, BorderLayout.NORTH);
        BrutalistStyle.styleTable(usersTable);
        usersTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        usersPanel.add(new JScrollPane(usersTable), BorderLayout.CENTER);

        JPanel auditPanel = new JPanel(new BorderLayout(0, 8));
        auditPanel.setBorder(BorderFactory.createEmptyBorder(10, 4, 4, 4));
        JPanel auditBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        auditBar.add(exportAuditBtn);
        auditPanel.add(auditBar, BorderLayout.NORTH);
        BrutalistStyle.styleTable(auditTable);
        auditPanel.add(new JScrollPane(auditTable), BorderLayout.CENTER);

        dbCard.setLayout(new BoxLayout(dbCard, BoxLayout.Y_AXIS));
        dbCard.setAlignmentX(LEFT_ALIGNMENT);
        dbCardTitle.setFont(ThemeManager.monoFont(Font.BOLD, 14f));
        dbInfoLabel.setFont(ThemeManager.monoFont(Font.PLAIN, 12f));
        JPanel dbBtns = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 6));
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
        i18nCard.add(i18nCardTitle);
        i18nCard.add(Box.createVerticalStrut(6));
        i18nCard.add(i18nInfoLabel);
        for (JButton button : List.of(exportI18nBtn, loadI18nBtn, resetI18nBtn)) {
            button.setAlignmentX(LEFT_ALIGNMENT);
            i18nCard.add(Box.createVerticalStrut(6));
            i18nCard.add(button);
        }

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

        sections.addSection("admin.tab.users", SidebarIcons.forView(MainFrame.VIEW_CUSTOMERS, 16), usersPanel);
        if (ctx.getSession().isSuperAdmin()) {
            modulesPanel = new AdminModulesPanel(ctx);
            sections.addSection("admin.tab.modules", SidebarIcons.forView(MainFrame.VIEW_ADMIN, 16), modulesPanel);
        }
        if (ctx.getSession().canManageUsersAndMaintenance()) {
            invoiceSettingsPanel = new InvoiceSettingsPanel(ctx);
            sections.addSection("invoice.tab.settings", AvatarBadge.createGearIcon(16), invoiceSettingsPanel);
        }
        if (ctx.getSession().isSuperAdmin()) {
            emailSettingsPanel = new AdminEmailSettingsPanel(ctx, this::refreshData);
            sections.addSection("admin.tab.mail", SidebarIcons.forView(MainFrame.VIEW_EMAIL, 16), emailSettingsPanel);
        }
        if (ctx.getSession().canManageUsersAndMaintenance()) {
            navIntegrationPanel = new NavIntegrationPanel(ctx);
            bankIntegrationPanel = new BankIntegrationPanel(ctx);
            sections.addSection("admin.tab.nav", SidebarIcons.mailDrafts(16), navIntegrationPanel);
            sections.addSection("admin.tab.bank", SidebarIcons.forView(MainFrame.VIEW_INVOICES, 16), bankIntegrationPanel);
        }
        sections.addSection("admin.tab.database", SidebarIcons.forView(MainFrame.VIEW_INVENTORY, 16), cardPage(dbCard));
        sections.addSection("admin.tab.translations", SidebarIcons.mailDrafts(16), cardPage(i18nCard));
        sections.addSection("admin.tab.audit", SidebarIcons.forView(MainFrame.VIEW_TODO, 16), auditPanel);
        sections.addSection("admin.tab.system", SidebarIcons.forView(MainFrame.VIEW_DASHBOARD, 16), systemPanel);
        sections.setSelectionListener(this::refreshSection);
        add(sections, BorderLayout.CENTER);
    }

    private void refreshSection(String section) {
        if (invoiceSettingsPanel != null && "invoice.tab.settings".equals(section)) invoiceSettingsPanel.refreshData();
        if (navIntegrationPanel != null && "admin.tab.nav".equals(section)) navIntegrationPanel.refreshData();
        if (bankIntegrationPanel != null && "admin.tab.bank".equals(section)) bankIntegrationPanel.refreshData();
    }

    private static JPanel cardPage(JPanel card) {
        JPanel page = new JPanel(new BorderLayout());
        page.setBorder(BorderFactory.createEmptyBorder(12, 4, 4, 4));
        page.add(card, BorderLayout.NORTH);
        return page;
    }

    void showTranslations() {
        sections.select("admin.tab.translations");
    }

    private void wireEvents() {
        addUserBtn.addActionListener(e -> openCreateUserDialog());
        usersTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int row = usersTable.rowAtPoint(event.getPoint());
                if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 2 && row >= 0) {
                    usersTable.setRowSelectionInterval(row, row);
                    openEditUserDialog();
                }
            }
        });
        usersTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "editSelectedUser");
        usersTable.getActionMap().put("editSelectedUser", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                openEditUserDialog();
            }
        });

        exportAuditBtn.addActionListener(e -> handleExportAuditCsv());

        backupDbBtn.addActionListener(e -> handleBackupDb());
        restoreDbBtn.addActionListener(e -> handleRestoreDb());

        exportI18nBtn.addActionListener(e -> handleExportTranslations());
        loadI18nBtn.addActionListener(e -> handleLoadTranslations());
        resetI18nBtn.addActionListener(e -> handleResetTranslations());
    }

    private User getSelectedUser() {
        int selected = usersTable.getSelectedRow();
        return selected < 0 ? null : users.get(usersTable.convertRowIndexToModel(selected));
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
        emailF.setEditable(false);
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
        addField(form, gbc, r++, i18n.t("profile.email"), emailF);
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
            ctx.getUserDao().updateEmail(createdId, emailF.getText());
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
        boolean canEditEmail = ctx.getSession().isSuperAdmin();
        boolean targetIsSuperAdmin = u.roleCode() == RoleCode.SUPER_ADMIN;
        if (!ctx.getSession().canManageUsersAndMaintenance() || (targetIsSuperAdmin && !canEditEmail)) {
            JOptionPane.showMessageDialog(this, i18n.t("admin.user.edit_denied"),
                    i18n.t("common.error"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<Role> roles = ctx.getRoleDao().findAll().stream()
                .filter(role -> targetIsSuperAdmin || role.code() != RoleCode.SUPER_ADMIN)
                .toList();

        JTextField lastNameF = new JTextField(u.safeLastName(), 18);
        JTextField firstNameF = new JTextField(u.safeFirstName(), 18);
        String initialEmail = u.email() == null ? "" : u.email().trim();
        JTextField emailF = new JTextField(initialEmail, 22);
        emailF.setEditable(canEditEmail);
        if (!canEditEmail) {
            emailF.setToolTipText(i18n.t("profile.email.managed"));
        }
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
            stationCombo.setEnabled(selRole != null && selRole.code() == RoleCode.OPERATOR);
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
        addField(form, gbc, r++, i18n.t("profile.email"), emailF);
        addField(form, gbc, r++, i18n.t("admin.user.role") + " *", roleCombo);
        addField(form, gbc, r++, i18n.t("admin.user.assigned_station"), stationCombo);
        gbc.gridx = 1; gbc.gridy = r++; form.add(activeBox, gbc);

        gbc.gridx = 0;
        gbc.gridy = r++;
        gbc.gridwidth = 2;
        JLabel resetTitle = new JLabel(i18n.t("admin.user.reset_pw"));
        resetTitle.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        resetTitle.setFont(ThemeManager.monoFont(Font.BOLD, 13f));
        form.add(resetTitle, gbc);
        gbc.gridwidth = 1;
        JPasswordField newPassword = new JPasswordField(18);
        JPasswordField confirmPassword = new JPasswordField(18);
        addField(form, gbc, r++, i18n.t("profile.password.new"), newPassword);
        addField(form, gbc, r, i18n.t("profile.password.confirm"), confirmPassword);

        String[] options = {i18n.t("common.save"), i18n.t("common.cancel")};
        while (JOptionPane.showOptionDialog(this, form, i18n.t("common.edit") + " — " + u.username(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]) == 0) {
            char[] password = newPassword.getPassword();
            char[] confirmation = confirmPassword.getPassword();
            try {
                if (!Arrays.equals(password, confirmation)) {
                    JOptionPane.showMessageDialog(this, i18n.t("profile.error.password_mismatch"),
                            i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
                    continue;
                }
                if (password.length > 0 && password.length < 6) {
                    JOptionPane.showMessageDialog(this, i18n.t("profile.error.password_short"),
                            i18n.t("common.warning"), JOptionPane.WARNING_MESSAGE);
                    continue;
                }
                Role role = (Role) roleCombo.getSelectedItem();
                if (role == null) {
                    return;
                }
                Long assignedId = stationCombo.getSelectedItem() instanceof StationOption so ? so.stationId() : null;
                String editedEmail = emailF.getText().trim();
                String emailChange = canEditEmail && !editedEmail.equals(initialEmail) ? editedEmail : null;
                ctx.getAuthService().updateManagedUser(u.id(), lastNameF.getText(), firstNameF.getText(),
                        role, activeBox.isSelected(), assignedId, emailChange, password);
                refreshData();
                return;
            } catch (Exception ex) {
                String message = switch (String.valueOf(ex.getMessage())) {
                    case "ADMIN_USER_EDIT_DENIED" -> i18n.t("admin.user.edit_denied");
                    case "ADMIN_USER_CANNOT_DISABLE_SELF" -> i18n.t("admin.user.cannot_disable_self");
                    default -> ex.getMessage();
                };
                JOptionPane.showMessageDialog(this, message, i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
            } finally {
                Arrays.fill(password, '\0');
                Arrays.fill(confirmation, '\0');
            }
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
        chooser.setSelectedFile(new File("rewear-backup-" + FILE_TS.format(BusinessClock.now()) + ".db"));
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
            new SchemaInitializer(ctx.getDatabaseManager()).initialize();
            JOptionPane.showMessageDialog(this, i18n.t("admin.db.restored"),
                    i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
            // The session still holds the user as they were before the restore. The backup may lack
            // that account or give it another role, so require a fresh login against the restored data.
            if (SwingUtilities.getWindowAncestor(this) instanceof MainFrame frame) {
                frame.performLogout();
            } else {
                refreshData();
            }
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
        String configuredPath = ctx.getPreferences().getExternalTranslationsPath();
        if (!configuredPath.isBlank()) chooser.setSelectedFile(new File(configuredPath));
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
        refreshSection(sections.selectedKey());
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
        var invoiceArchive = ctx.getInvoiceArchiveService();
        var i18n = ctx.getI18n();
        String theme = ThemeManager.isDarkMode() ? "DARK" : "LIGHT";

        runtimeInfoLabel.setText("<html>"
                + "<b>Java:</b> " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")"
                + " &nbsp;|&nbsp; <b>OS:</b> " + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + " &nbsp;|&nbsp; <b>Heap:</b> " + usedMb + " / " + maxMb + " MB<br>"
                + "<b>SQLite DB:</b> " + dbPath + " (" + dbKb + " KB)"
                + "<br><b>" + i18n.t("admin.system.invoice_archive") + ":</b> " + invoiceArchive.getDirectory()
                + " (" + i18n.t("admin.system.file_count", invoiceArchive.countInvoices()) + ")<br>"
                + "<b>" + i18n.t("admin.system.receipt_vault") + ":</b> " + ctx.getReceiptVaultService().getReceiptsDir()
                + " (" + i18n.t("admin.system.file_count", receiptCount) + ")<br>"
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
        sections.applyTranslations();
        if (invoiceSettingsPanel != null) invoiceSettingsPanel.applyTranslations();
        if (navIntegrationPanel != null) navIntegrationPanel.applyTranslations();
        if (bankIntegrationPanel != null) bankIntegrationPanel.applyTranslations();
        if (emailSettingsPanel != null) {
            emailSettingsPanel.applyTranslations();
        }
        if (modulesPanel != null) {
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
        usersTable.getAccessibleContext().setAccessibleDescription(i18n.t("admin.user.edit_hint"));
        usersTable.setToolTipText(i18n.t("admin.user.edit_hint"));

        exportAuditBtn.setText(i18n.t("common.export.csv").toUpperCase());

        dbCardTitle.setText(i18n.t("admin.tab.database").toUpperCase(Locale.ROOT));
        backupDbBtn.setText(i18n.t("admin.db.backup").toUpperCase());
        restoreDbBtn.setText(i18n.t("admin.db.restore").toUpperCase());

        i18nCardTitle.setText(i18n.t("admin.tab.translations").toUpperCase(Locale.ROOT));
        exportI18nBtn.setText(i18n.t("admin.i18n.export").toUpperCase());
        loadI18nBtn.setText(i18n.t("admin.i18n.reload").toUpperCase());
        resetI18nBtn.setText(i18n.t("admin.i18n.reset").toUpperCase());

        usersModel.setColumnIdentifiers(new Object[]{
                i18n.t("login.username").toUpperCase(),
                i18n.t("admin.user.full_name").toUpperCase(),
                i18n.t("profile.email").toUpperCase(),
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
        sections.applyTheme();
        if (invoiceSettingsPanel != null) invoiceSettingsPanel.applyTheme();
        if (navIntegrationPanel != null) navIntegrationPanel.applyTheme();
        if (bankIntegrationPanel != null) bankIntegrationPanel.applyTheme();
        BrutalistStyle.stylePrimaryButton(addUserBtn);
        BrutalistStyle.stylePrimaryButton(backupDbBtn);
        BrutalistStyle.stylePrimaryButton(loadI18nBtn);
        BrutalistStyle.styleSecondaryButtons(
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
