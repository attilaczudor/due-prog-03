package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.Customer;
import hu.due.rewear.model.EmailMessage;
import hu.due.rewear.model.User;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Brutalist Monochrome corporate email client supporting internal company delivery
 * ({@code @rewear-wms.hu}) and external SMTP/IMAP partner communications.
 */
public final class EmailView extends JPanel implements NavigableView {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AppContext ctx;

    private final JSeparator folderSeparator = new JSeparator(SwingConstants.HORIZONTAL);
    private final JToggleButton inboxBtn = new JToggleButton();
    private final JToggleButton sentBtn = new JToggleButton();
    private final JToggleButton draftsBtn = new JToggleButton();
    private final JToggleButton trashBtn = new JToggleButton();
    private final ButtonGroup folderGroup = new ButtonGroup();

    private final JButton composeBtn = new JButton();
    private final JButton replyBtn = new JButton();
    private final JButton forwardBtn = new JButton();
    private final JButton toggleReadBtn = new JButton();
    private final JButton deleteBtn = new JButton();
    private final JButton simIncomingBtn = new JButton();

    private final DefaultTableModel listModel = BrutalistStyle.nonEditableTableModel();
    private final JTable listTable = new JTable(listModel);

    private final JPanel readerCard = new JPanel(new BorderLayout(0, 8));
    private final JLabel readerSubjectLabel = new JLabel("—");
    private final JLabel readerMetaLabel = new JLabel(" ");
    private final JButton openAttachmentBtn = new JButton();
    private final JTextArea readerBodyArea = new JTextArea();

    private String activeFolder = EmailMessage.FOLDER_INBOX;
    private List<EmailMessage> currentEmails = List.of();

    public EmailView(AppContext ctx) {
        this.ctx = ctx;
        setLayout(new BorderLayout(12, 10));
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        buildUi();
        wireEvents();
        applyTranslations();
        applyTheme();
    }

    private void buildUi() {
        // Left folder rail (+ New Email -> padded horizontal rule -> Folders)
        JPanel folderPanel = new JPanel();
        folderPanel.setLayout(new BoxLayout(folderPanel, BoxLayout.Y_AXIS));
        folderPanel.setPreferredSize(new Dimension(210, 0));
        BrutalistStyle.applyBoxCard(folderPanel, 10, 10);

        composeBtn.setAlignmentX(LEFT_ALIGNMENT);
        composeBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        composeBtn.setPreferredSize(new Dimension(180, 36));
        folderPanel.add(composeBtn);

        folderPanel.add(Box.createVerticalStrut(10));
        folderSeparator.setAlignmentX(LEFT_ALIGNMENT);
        folderSeparator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        folderPanel.add(folderSeparator);
        folderPanel.add(Box.createVerticalStrut(10));

        for (JToggleButton btn : List.of(inboxBtn, sentBtn, draftsBtn, trashBtn)) {
            btn.setHorizontalAlignment(SwingConstants.LEFT);
            btn.setAlignmentX(LEFT_ALIGNMENT);
            btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
            btn.setPreferredSize(new Dimension(180, 34));
            folderGroup.add(btn);
            folderPanel.add(btn);
            folderPanel.add(Box.createVerticalStrut(4));
        }
        inboxBtn.setSelected(true);
        folderPanel.add(Box.createVerticalGlue());

        simIncomingBtn.setAlignmentX(LEFT_ALIGNMENT);
        simIncomingBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        simIncomingBtn.setPreferredSize(new Dimension(180, 32));
        folderPanel.add(simIncomingBtn);
        add(folderPanel, BorderLayout.WEST);

        // Center split: list + reader
        BrutalistStyle.styleTable(listTable);
        listTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JPanel readerHeader = new JPanel(new BorderLayout(8, 4));
        readerHeader.setOpaque(false);
        readerSubjectLabel.setFont(ThemeManager.monoFont(Font.BOLD, 14f));
        readerMetaLabel.setFont(ThemeManager.monoFont(Font.PLAIN, 11.5f));
        JPanel headerText = new JPanel(new BorderLayout(0, 4));
        headerText.setOpaque(false);
        headerText.add(readerSubjectLabel, BorderLayout.NORTH);
        headerText.add(readerMetaLabel, BorderLayout.CENTER);
        readerHeader.add(headerText, BorderLayout.CENTER);
        openAttachmentBtn.setVisible(false);
        readerHeader.add(openAttachmentBtn, BorderLayout.EAST);

        readerBodyArea.setEditable(false);
        readerBodyArea.setLineWrap(true);
        readerBodyArea.setWrapStyleWord(true);
        readerBodyArea.setFont(ThemeManager.monoFont(Font.PLAIN, 12.5f));
        readerBodyArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel readerActionsBar = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 2));
        readerActionsBar.setOpaque(false);
        readerActionsBar.add(replyBtn);
        readerActionsBar.add(forwardBtn);
        readerActionsBar.add(toggleReadBtn);
        readerActionsBar.add(deleteBtn);

        readerCard.add(readerHeader, BorderLayout.NORTH);
        readerCard.add(new JScrollPane(readerBodyArea), BorderLayout.CENTER);
        readerCard.add(readerActionsBar, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(listTable), readerCard);
        split.setResizeWeight(0.45);
        split.setDividerLocation(260);
        add(split, BorderLayout.CENTER);
    }

    private void wireEvents() {
        inboxBtn.addActionListener(e -> switchFolder(EmailMessage.FOLDER_INBOX));
        sentBtn.addActionListener(e -> switchFolder(EmailMessage.FOLDER_SENT));
        draftsBtn.addActionListener(e -> switchFolder(EmailMessage.FOLDER_DRAFTS));
        trashBtn.addActionListener(e -> switchFolder(EmailMessage.FOLDER_TRASH));

        composeBtn.addActionListener(e -> openComposeDialog("", "", "", ""));
        replyBtn.addActionListener(e -> {
            EmailMessage m = getSelectedEmail();
            if (m != null) {
                String subj = m.subject().toLowerCase(Locale.ROOT).startsWith("re:") ? m.subject() : "Re: " + m.subject();
                String quote = "\n\n--- " + m.fromAddress() + " ---\n" + m.body();
                openComposeDialog(m.fromAddress(), "", subj, quote);
            }
        });
        forwardBtn.addActionListener(e -> {
            EmailMessage m = getSelectedEmail();
            if (m != null) {
                String subj = m.subject().toLowerCase(Locale.ROOT).startsWith("fwd:") ? m.subject() : "Fwd: " + m.subject();
                String quote = "\n\n--- Forwarded from " + m.fromAddress() + " ---\n" + m.body();
                openComposeDialog("", "", subj, quote);
            }
        });
        toggleReadBtn.addActionListener(e -> {
            EmailMessage m = getSelectedEmail();
            if (m != null) {
                ctx.getEmailService().markRead(m.id(), !m.read());
                refreshData();
            }
        });
        deleteBtn.addActionListener(e -> {
            EmailMessage m = getSelectedEmail();
            if (m != null) {
                ctx.getEmailService().moveToTrashOrDelete(m);
                refreshData();
            }
        });
        simIncomingBtn.addActionListener(e -> handleSimulateExternalIncoming());

        listTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                renderSelectedEmail();
            }
        });

        openAttachmentBtn.addActionListener(e -> {
            EmailMessage m = getSelectedEmail();
            if (m != null && m.hasAttachment()) {
                try {
                    Path p = Path.of(m.attachmentPath());
                    if (Files.exists(p) && Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().open(p.toFile());
                    } else {
                        JOptionPane.showMessageDialog(this, m.attachmentPath(),
                                ctx.getI18n().t("email.attachment"), JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, ex.getMessage(),
                            ctx.getI18n().t("common.error"), JOptionPane.ERROR_MESSAGE);
                }
            }
        });
    }

    private void switchFolder(String folder) {
        this.activeFolder = folder;
        refreshData();
    }

    private EmailMessage getSelectedEmail() {
        int r = listTable.getSelectedRow();
        return (r >= 0 && r < currentEmails.size()) ? currentEmails.get(r) : null;
    }

    private void renderSelectedEmail() {
        EmailMessage m = getSelectedEmail();
        I18nManager i18n = ctx.getI18n();
        boolean hasSelection = (m != null);
        replyBtn.setEnabled(hasSelection);
        forwardBtn.setEnabled(hasSelection);
        toggleReadBtn.setEnabled(hasSelection);
        deleteBtn.setEnabled(hasSelection);
        if (m == null) {
            readerSubjectLabel.setText("—");
            readerMetaLabel.setText(" ");
            readerBodyArea.setText("");
            openAttachmentBtn.setVisible(false);
            return;
        }
        if (!m.read() && EmailMessage.FOLDER_INBOX.equalsIgnoreCase(m.folder())) {
            ctx.getEmailService().markRead(m.id(), true);
            refreshFolderBadges();
        }
        readerSubjectLabel.setText(m.subject());
        String ts = m.createdAt() != null ? DT_FMT.format(m.createdAt()) : "";
        readerMetaLabel.setText("<html>"
                + "<b>" + i18n.t("email.from") + ":</b> " + m.fromAddress()
                + " &nbsp;|&nbsp; <b>" + i18n.t("email.to") + ":</b> " + m.toAddresses()
                + (m.ccAddresses() != null && !m.ccAddresses().isBlank()
                        ? " &nbsp;|&nbsp; <b>CC:</b> " + m.ccAddresses() : "")
                + "<br><b>" + i18n.t("common.date") + ":</b> " + ts
                + " &nbsp;|&nbsp; <b>" + i18n.t("common.status") + ":</b> " + m.deliveryStatus()
                + "</html>");
        readerBodyArea.setText(m.body());
        readerBodyArea.setCaretPosition(0);
        if (m.hasAttachment()) {
            openAttachmentBtn.setText("[PDF] " + Path.of(m.attachmentPath()).getFileName());
            openAttachmentBtn.setVisible(true);
        } else {
            openAttachmentBtn.setVisible(false);
        }
    }

    private void openComposeDialog(String initialTo, String initialCc, String initialSubject, String initialBody) {
        I18nManager i18n = ctx.getI18n();
        JTextField toField = new JTextField(initialTo, 34);
        JTextField ccField = new JTextField(initialCc, 34);
        JTextField subjField = new JTextField(initialSubject, 34);
        JTextArea bodyArea = new JTextArea(initialBody, 10, 34);
        bodyArea.setLineWrap(true);
        bodyArea.setWrapStyleWord(true);
        JTextField attachField = new JTextField("", 24);
        attachField.setEditable(false);
        JButton browseBtn = new JButton(i18n.t("email.attach"));
        BrutalistStyle.styleSecondaryButton(browseBtn);
        browseBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File f = fc.getSelectedFile();
                attachField.setText(f.getAbsolutePath());
            }
        });

        // Quick-pick recipient directory (company teammates; B2B external partners only for Manager/Admin/SuperAdmin)
        JComboBox<String> directoryCombo = new JComboBox<>();
        directoryCombo.addItem("— " + i18n.t("email.quick_pick") + " —");
        for (User u : ctx.getUserDao().findAll()) {
            if (u.email() != null && !u.email().isBlank()) {
                directoryCombo.addItem(u.displayName() + " <" + u.email() + ">");
            }
        }
        if (ctx.getSession().canUseExternalEmail()) {
            for (Customer c : ctx.getCustomerDao().findAll(true)) {
                if (c.email() != null && !c.email().isBlank()) {
                    directoryCombo.addItem(c.name() + " <" + c.email() + ">");
                }
            }
        }
        directoryCombo.addActionListener(e -> {
            String sel = (String) directoryCombo.getSelectedItem();
            if (sel != null && sel.contains("<") && sel.contains(">")) {
                String addr = sel.substring(sel.indexOf('<') + 1, sel.indexOf('>'));
                String cur = toField.getText().trim();
                toField.setText(cur.isEmpty() ? addr : cur + ", " + addr);
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int r = 0;
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("email.directory"), directoryCombo);
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
        int res = JOptionPane.showOptionDialog(this, form, i18n.t("email.compose"),
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        User me = ctx.getSession().requireCurrentUser();
        String attach = attachField.getText().isBlank() ? null : attachField.getText().trim();
        try {
            if (res == 0) {
                ctx.getEmailService().sendEmail(me, toField.getText(), ccField.getText(),
                        subjField.getText(), bodyArea.getText(), attach);
                refreshData();
                JOptionPane.showMessageDialog(this, i18n.t("email.sent_ok"),
                        i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
            } else if (res == 1) {
                ctx.getEmailService().saveDraft(me, toField.getText(), ccField.getText(),
                        subjField.getText(), bodyArea.getText(), attach);
                refreshData();
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleSimulateExternalIncoming() {
        I18nManager i18n = ctx.getI18n();
        User me = ctx.getSession().requireCurrentUser();
        ctx.getEmailService().simulateIncomingExternalEmail(me,
                "supply@greenloop.sk",
                "EUR Shipment Inquiry — Grade A & Cream Bales",
                "Hello " + me.displayName() + ",\n\n"
                        + "We would like to book 6 compressed bales (Grade A) for Friday pickup at DOCK-01.\n"
                        + "Please reply with availability and your EUR 0% EU-0 proforma.\n\n"
                        + "Best regards,\nJana Nováková — GreenLoop Second-Hand S.R.O.");
        activeFolder = EmailMessage.FOLDER_INBOX;
        inboxBtn.setSelected(true);
        refreshData();
        JOptionPane.showMessageDialog(this, i18n.t("email.sim_received"),
                i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
    }

    private void refreshFolderBadges() {
        I18nManager i18n = ctx.getI18n();
        long uid = ctx.getSession().requireCurrentUser().id();
        int unread = ctx.getEmailService().countUnreadInbox(uid);
        inboxBtn.setText(i18n.t("email.folder.inbox").toUpperCase(Locale.ROOT)
                + (unread > 0 ? "  [" + unread + "]" : ""));
        sentBtn.setText(i18n.t("email.folder.sent").toUpperCase(Locale.ROOT));
        draftsBtn.setText(i18n.t("email.folder.drafts").toUpperCase(Locale.ROOT));
        trashBtn.setText(i18n.t("email.folder.trash").toUpperCase(Locale.ROOT));
    }

    @Override
    public String getViewKey() {
        return MainFrame.VIEW_EMAIL;
    }

    @Override
    public String getTitleI18nKey() {
        return "email.title";
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    @Override
    public void refreshData() {
        User me = ctx.getSession().requireCurrentUser();
        boolean externalAllowed = ctx.getSession().canUseExternalEmail();
        simIncomingBtn.setVisible(externalAllowed);
        refreshFolderBadges();

        currentEmails = ctx.getEmailService().listFolder(me.id(), activeFolder);
        listModel.setRowCount(0);
        for (EmailMessage m : currentEmails) {
            String peer = EmailMessage.FOLDER_SENT.equalsIgnoreCase(activeFolder)
                    || EmailMessage.FOLDER_DRAFTS.equalsIgnoreCase(activeFolder)
                    ? m.toAddresses()
                    : m.fromAddress();
            String badge = m.read() ? "✓" : "●";
            String subj = (m.hasAttachment() ? "[PDF] " : "") + m.subject();
            listModel.addRow(new Object[]{
                    badge,
                    peer,
                    subj,
                    m.deliveryStatus(),
                    m.createdAt() != null ? DT_FMT.format(m.createdAt()) : ""
            });
        }
        configureTableColumnWidths();
        if (!currentEmails.isEmpty()) {
            listTable.setRowSelectionInterval(0, 0);
        } else {
            renderSelectedEmail();
        }
    }

    private void configureTableColumnWidths() {
        if (listTable.getColumnCount() < 5) {
            return;
        }
        var cm = listTable.getColumnModel();
        cm.getColumn(0).setPreferredWidth(44);
        cm.getColumn(0).setMaxWidth(52);
        cm.getColumn(0).setMinWidth(40);
        var center = new javax.swing.table.DefaultTableCellRenderer();
        center.setHorizontalAlignment(SwingConstants.CENTER);
        cm.getColumn(0).setCellRenderer(center);
        cm.getColumn(1).setPreferredWidth(210);
        cm.getColumn(2).setPreferredWidth(280);
        cm.getColumn(3).setPreferredWidth(160);
        cm.getColumn(4).setPreferredWidth(135);
    }

    @Override
    public void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        composeBtn.setText("+ " + i18n.t("email.compose").toUpperCase(Locale.ROOT));
        replyBtn.setText(i18n.t("email.reply").toUpperCase(Locale.ROOT));
        forwardBtn.setText(i18n.t("email.forward").toUpperCase(Locale.ROOT));
        toggleReadBtn.setText(i18n.t("email.toggle_read").toUpperCase(Locale.ROOT));
        deleteBtn.setText(i18n.t("common.delete").toUpperCase(Locale.ROOT));
        simIncomingBtn.setText(i18n.t("email.sim_btn").toUpperCase(Locale.ROOT));

        listModel.setColumnIdentifiers(new Object[]{
                "●",
                i18n.t("email.from_to").toUpperCase(Locale.ROOT),
                i18n.t("email.subject").toUpperCase(Locale.ROOT),
                i18n.t("common.status").toUpperCase(Locale.ROOT),
                i18n.t("common.date").toUpperCase(Locale.ROOT)
        });
        configureTableColumnWidths();
        refreshFolderBadges();
        applyTheme();
    }

    @Override
    public void applyTheme() {
        BrutalistStyle.stylePrimaryButton(composeBtn);
        BrutalistStyle.styleSecondaryButtons(replyBtn, forwardBtn, toggleReadBtn, deleteBtn,
                simIncomingBtn, openAttachmentBtn);
        folderSeparator.setForeground(ThemeManager.border());
        folderSeparator.setBackground(ThemeManager.border());
        BrutalistStyle.applyBoxCard(readerCard, 10, 12);
        BrutalistStyle.styleTable(listTable);
        readerBodyArea.setBackground(ThemeManager.bg());
        readerBodyArea.setForeground(ThemeManager.fg());
    }
}
