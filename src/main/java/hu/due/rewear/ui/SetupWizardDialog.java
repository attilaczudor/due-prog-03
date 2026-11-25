package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.SchemaInitializer;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.service.BackupService;
import hu.due.rewear.service.OnboardingService.CompanySetup;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import javax.swing.JComponent;
import javax.swing.WindowConstants;

/**
 * Shown once, on an installation that has no users yet. The owner chooses between their own company
 * (an empty workspace with a single administrator account they define) and the demo workspace, or
 * restores an existing backup. Closing the window without choosing quits the application, since there
 * is nobody who could sign in.
 */
public final class SetupWizardDialog extends JDialog {

    private final AppContext ctx;

    private final JRadioButton companyChoice = new JRadioButton();
    private final JRadioButton demoChoice = new JRadioButton();
    private final JPanel companyForm = new JPanel(new GridBagLayout());
    private final JLabel demoWarning = new JLabel();
    private final JTextField companyField = new JTextField(26);
    private final JTextField lastNameField = new JTextField(12);
    private final JTextField firstNameField = new JTextField(12);
    private final JTextField usernameField = new JTextField(16);
    private final JTextField emailField = new JTextField(26);
    private final JPasswordField passwordField = new JPasswordField(20);
    private final JPasswordField confirmField = new JPasswordField(20);
    private final JCheckBox catalogsBox = new JCheckBox();
    private final JCheckBox locationsBox = new JCheckBox();
    private final JButton continueBtn = new JButton();
    private final JButton restoreBtn = new JButton();
    private final JButton quitBtn = new JButton();

    private boolean completed;

    public SetupWizardDialog(AppContext ctx) {
        super((java.awt.Frame) null, ctx.getI18n().t("setup.title"), true);
        this.ctx = ctx;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                quit();
            }
        });
        buildUi();
        pack();
        setLocationRelativeTo(null);
    }

    /** @return true once the installation has users (a company, the demo, or a restored backup) */
    public boolean showDialog() {
        setVisible(true);
        return completed;
    }

    private void buildUi() {
        I18nManager i18n = ctx.getI18n();
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(18, 24, 16, 24));

        JLabel intro = new JLabel("<html><body style='width:460px'>" + escape(i18n.t("setup.intro")) + "</body></html>");
        root.add(intro, BorderLayout.NORTH);

        JPanel center = new JPanel();
        center.setLayout(new javax.swing.BoxLayout(center, javax.swing.BoxLayout.Y_AXIS));

        ButtonGroup group = new ButtonGroup();
        group.add(companyChoice);
        group.add(demoChoice);
        companyChoice.setText(i18n.t("setup.mode.company"));
        companyChoice.setSelected(true);
        demoChoice.setText(i18n.t("setup.mode.demo"));
        companyChoice.addActionListener(e -> refreshChoice());
        demoChoice.addActionListener(e -> refreshChoice());

        center.add(left(companyChoice));
        center.add(left(hint(i18n.t("setup.mode.company.desc"))));
        buildCompanyForm(i18n);
        center.add(left(companyForm));
        center.add(javax.swing.Box.createVerticalStrut(8));
        center.add(left(demoChoice));
        demoWarning.setText("<html><body style='width:460px'>" + escape(i18n.t("setup.mode.demo.desc")) + "</body></html>");
        demoWarning.setFont(demoWarning.getFont().deriveFont(11f));
        center.add(left(demoWarning));
        root.add(center, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        quitBtn.setText(i18n.t("setup.quit"));
        restoreBtn.setText(i18n.t("setup.restore"));
        continueBtn.setText(i18n.t("setup.continue"));
        BrutalistStyle.styleSecondaryButton(quitBtn);
        BrutalistStyle.styleSecondaryButton(restoreBtn);
        BrutalistStyle.stylePrimaryButton(continueBtn);
        quitBtn.addActionListener(e -> quit());
        restoreBtn.addActionListener(e -> restoreBackup());
        continueBtn.addActionListener(e -> proceed());
        buttons.add(quitBtn);
        buttons.add(restoreBtn);
        buttons.add(continueBtn);
        root.add(buttons, BorderLayout.SOUTH);

        setContentPane(root);
        getRootPane().setDefaultButton(continueBtn);
        refreshChoice();
    }

    private void buildCompanyForm(I18nManager i18n) {
        companyForm.setBorder(BorderFactory.createEmptyBorder(6, 22, 0, 0));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        int row = 0;
        row = field(gbc, row, i18n.t("setup.company_name") + " *", companyField, 3);
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0; gbc.gridwidth = 1;
        companyForm.add(new JLabel(i18n.t("setup.last_name") + " *"), gbc);
        JPanel names = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        names.add(lastNameField);
        names.add(javax.swing.Box.createHorizontalStrut(8));
        names.add(new JLabel(i18n.t("setup.first_name") + "  "));
        names.add(firstNameField);
        gbc.gridx = 1; gbc.weightx = 1; gbc.gridwidth = 2;
        companyForm.add(names, gbc);
        row++;
        row = field(gbc, row, i18n.t("setup.username") + " *", usernameField, 2);
        row = field(gbc, row, i18n.t("setup.email"), emailField, 3);
        row = field(gbc, row, i18n.t("setup.password") + " *", passwordField, 2);
        row = field(gbc, row, i18n.t("setup.password_confirm") + " *", confirmField, 2);
        catalogsBox.setText(i18n.t("setup.catalogs"));
        catalogsBox.setSelected(true);
        locationsBox.setText(i18n.t("setup.locations"));
        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 3; gbc.weightx = 1;
        companyForm.add(catalogsBox, gbc);
        gbc.gridy = row;
        companyForm.add(locationsBox, gbc);
    }

    private int field(GridBagConstraints gbc, int row, String label, JComponent input, int span) {
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0; gbc.gridwidth = 1;
        companyForm.add(new JLabel(label), gbc);
        gbc.gridx = 1; gbc.weightx = 1; gbc.gridwidth = span - 1 <= 0 ? 1 : span - 1;
        companyForm.add(input, gbc);
        return row + 1;
    }

    private static JLabel hint(String text) {
        JLabel label = new JLabel("<html><body style='width:440px'>" + escape(text) + "</body></html>");
        label.setFont(label.getFont().deriveFont(11f));
        label.setBorder(BorderFactory.createEmptyBorder(0, 22, 0, 0));
        return label;
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        return c;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void refreshChoice() {
        boolean company = companyChoice.isSelected();
        for (java.awt.Component c : companyForm.getComponents()) {
            setEnabledDeep(c, company);
        }
        demoWarning.setEnabled(!company);
        pack();
    }

    private static void setEnabledDeep(java.awt.Component c, boolean enabled) {
        c.setEnabled(enabled);
        if (c instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                setEnabledDeep(child, enabled);
            }
        }
    }

    // ---- actions ----------------------------------------------------------------------------

    private void proceed() {
        I18nManager i18n = ctx.getI18n();
        if (demoChoice.isSelected()) {
            runBusy(() -> {
                ctx.getOnboardingService().startDemoWorkspace();
                return null;
            });
            return;
        }
        char[] password = passwordField.getPassword();
        char[] confirm = confirmField.getPassword();
        boolean same = Arrays.equals(password, confirm);
        Arrays.fill(confirm, '\0');
        if (!same) {
            Arrays.fill(password, '\0');
            warn(i18n.t("setup.error.mismatch"));
            return;
        }
        CompanySetup setup = new CompanySetup(companyField.getText(), lastNameField.getText(),
                firstNameField.getText(), usernameField.getText(), emailField.getText(), password,
                catalogsBox.isSelected(), locationsBox.isSelected());
        runBusy(() -> {
            ctx.getOnboardingService().createCompanyWorkspace(setup);
            return null;
        });
    }

    private void restoreBackup() {
        I18nManager i18n = ctx.getI18n();
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("ReWear backup (*.zip, *.db)", "zip", "db"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path chosen = chooser.getSelectedFile().toPath();
        runBusy(() -> {
            if (BackupService.isBundle(chosen)) {
                ctx.getBackupService().restoreBundle(chosen);
            } else {
                ctx.getBackupService().restoreDatabase(chosen);
            }
            new SchemaInitializer(ctx.getDatabaseManager()).initialize();
            ctx.getOnboardingService().recordModeOfExistingDatabase();
            if (ctx.getOnboardingService().needsSetup()) {
                throw new IllegalStateException(i18n.t("setup.restore.empty"));
            }
            return null;
        });
    }

    /** Runs a slow step off the event thread (password hashing, seeding, restoring) and reports its outcome. */
    private void runBusy(Callable<Void> work) {
        setBusy(true);
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                return work.call();
            }

            @Override protected void done() {
                setBusy(false);
                try {
                    get();
                    completed = ctx.getOnboardingService().needsSetup() == false;
                    if (completed) {
                        dispose();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    warn(cause.getMessage() == null ? cause.toString() : cause.getMessage());
                }
            }
        }.execute();
    }

    private void setBusy(boolean busy) {
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
        continueBtn.setEnabled(!busy);
        restoreBtn.setEnabled(!busy);
        quitBtn.setEnabled(!busy);
    }

    private void warn(String message) {
        JOptionPane.showMessageDialog(this, message, ctx.getI18n().t("common.warning"), JOptionPane.WARNING_MESSAGE);
    }

    private void quit() {
        dispose();
        System.exit(0);
    }
}
