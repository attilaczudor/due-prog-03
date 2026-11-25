package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.Customer;
import hu.due.rewear.model.User;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class EmailComposeDialog {
    private EmailComposeDialog() { }

    static void show(Component owner, AppContext ctx, String initialTo, String initialCc,
                     String initialSubject, String initialBody, Path document, Runnable onSaved) {
        I18nManager i18n = ctx.getI18n();
        boolean externalAllowed = ctx.getSession().canUseExternalEmail();

        // Build searchable recipient directory (company teammates; B2B partners only for external-capable roles)
        List<RecipientMultiSelectField.ContactOption> contacts = new ArrayList<>();
        for (User u : ctx.getUserDao().findAll()) {
            if (u.email() != null && !u.email().isBlank()) {
                contacts.add(new RecipientMultiSelectField.ContactOption(u.displayName(), u.email()));
            }
        }
        if (externalAllowed) {
            for (Customer c : ctx.getCustomerDao().findAll(true)) {
                if (c.email() != null && !c.email().isBlank()) {
                    contacts.add(new RecipientMultiSelectField.ContactOption(c.name(), c.email()));
                }
            }
        }

        String searchPlaceholder = i18n.t("email.recipient_search_placeholder");
        RecipientMultiSelectField toField = new RecipientMultiSelectField(
                contacts, externalAllowed, searchPlaceholder, initialTo);
        RecipientMultiSelectField ccField = new RecipientMultiSelectField(
                contacts, externalAllowed, searchPlaceholder, initialCc);

        JTextField subjField = new JTextField(initialSubject, 34);
        subjField.setFont(ThemeManager.monoFont(Font.PLAIN, 12.5f));
        subjField.setPreferredSize(new Dimension(subjField.getPreferredSize().width, 32));
        subjField.setMinimumSize(new Dimension(0, 32));
        JTextArea bodyArea = new JTextArea(initialBody, 10, 34);
        bodyArea.setFont(ThemeManager.monoFont(Font.PLAIN, 12.5f));
        bodyArea.setLineWrap(true);
        bodyArea.setWrapStyleWord(true);
        Path[] attachment = {document};
        JTextField attachField = new JTextField(document == null ? "" : document.getFileName().toString(), 16);
        if (document != null) attachField.setToolTipText(document.toString());
        attachField.setFont(ThemeManager.monoFont(Font.PLAIN, 12.5f));
        attachField.setEditable(false);
        attachField.setPreferredSize(new Dimension(attachField.getPreferredSize().width, 32));
        attachField.setMinimumSize(new Dimension(0, 32));
        JButton browseBtn = new JButton(i18n.t(document == null ? "email.attach" : "docpreview.email.location"));
        BrutalistStyle.styleSecondaryButton(browseBtn);
        browseBtn.setPreferredSize(new Dimension(browseBtn.getPreferredSize().width, 32));
        browseBtn.setMinimumSize(new Dimension(browseBtn.getPreferredSize().width, 32));
        browseBtn.addActionListener(e -> {
            if (document != null) {
                openFolder(owner, i18n, document);
                return;
            }
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
                attachment[0] = fc.getSelectedFile().toPath().toAbsolutePath();
                attachField.setText(attachment[0].getFileName().toString());
                attachField.setToolTipText(attachment[0].toString());
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        form.setPreferredSize(new Dimension(540, 420));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int r = 0;
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("email.to") + " *", toField);
        BrutalistStyle.addFormRow(form, gbc, r++, "CC", ccField);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("email.subject") + " *", subjField);
        JPanel attachRow = new JPanel(new BorderLayout(6, 0));
        attachRow.add(attachField, BorderLayout.CENTER);
        attachRow.add(browseBtn, BorderLayout.EAST);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("email.attachment"), attachRow);
        gbc.gridx = 0; gbc.gridy = r; gbc.gridwidth = 2; gbc.weightx = 1; gbc.weighty = 1;
        gbc.fill = GridBagConstraints.BOTH;
        form.add(new JScrollPane(bodyArea), gbc);

        String[] options = {i18n.t("email.send"), i18n.t("email.save_draft"), i18n.t("common.cancel")};
        String title = i18n.t(document == null ? "email.compose" : "docpreview.email.title");
        while (true) {
            int result = JOptionPane.showOptionDialog(owner, form, title,
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
            if (result != 0 && result != 1) return;

            User sender = ctx.getSession().requireCurrentUser();
            String to = toField.getSelectedAddressesCsv();
            String cc = ccField.getSelectedAddressesCsv();
            String path = attachment[0] == null ? null : attachment[0].toString();
            boolean simulatedExternal = false;
            try {
                if (result == 0) {
                    simulatedExternal = ctx.getEmailService().sendEmail(sender, to, cc,
                            subjField.getText(), bodyArea.getText(), path);
                } else {
                    ctx.getEmailService().saveDraft(sender, to, cc, subjField.getText(), bodyArea.getText(), path);
                }
            } catch (RuntimeException ex) {
                JOptionPane.showMessageDialog(owner, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
                continue;
            }
            onSaved.run();
            if (result == 0) {
                JOptionPane.showMessageDialog(owner,
                        i18n.t(simulatedExternal ? "email.sent_simulated" : "email.sent_ok"),
                        i18n.t(simulatedExternal ? "common.warning" : "common.success"),
                        simulatedExternal ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
            }
            return;
        }
    }

    private static void openFolder(Component owner, I18nManager i18n, Path document) {
        Path folder = document.toAbsolutePath().getParent();
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(folder.toFile());
                return;
            }
        } catch (IOException | SecurityException ex) {
            JOptionPane.showMessageDialog(owner, folder + "\n" + ex.getMessage(),
                    i18n.t("docpreview.email.location"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        JOptionPane.showMessageDialog(owner, folder, i18n.t("docpreview.email.location"), JOptionPane.INFORMATION_MESSAGE);
    }
}
