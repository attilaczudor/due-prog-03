package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.EmailMessage;
import hu.due.rewear.model.SmartFolder;
import hu.due.rewear.model.User;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Brutalist Monochrome corporate email client supporting internal company delivery
 * ({@code @rewear-wms.hu}) and external SMTP/IMAP partner communications.
 */
public final class EmailView extends JPanel implements NavigableView {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AppContext ctx;

    private static final List<String> MAILBOXES = List.of(EmailMessage.FOLDER_INBOX, EmailMessage.FOLDER_SENT,
            EmailMessage.FOLDER_DRAFTS, EmailMessage.FOLDER_TRASH);

    private final WorkspaceFolderRail folders;
    private final JButton smartFoldersButton = new JButton(SidebarIcons.smartFolder(16));
    private List<SmartFolder> smartFolders = List.of();

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
    private boolean updatingMailbox;

    public EmailView(AppContext ctx) {
        this.ctx = ctx;
        folders = new WorkspaceFolderRail(ctx.getI18n(), "email.compose", 210,
                () -> openComposeDialog("", "", "", ""), this::switchFolder);
        setLayout(new BorderLayout(12, 10));
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        buildUi();
        wireEvents();
        applyTranslations();
        applyTheme();
    }

    private void buildUi() {
        folders.addUtility(simIncomingBtn);
        folders.addFooterButton(smartFoldersButton);
        add(folders, BorderLayout.WEST);

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
        smartFoldersButton.addActionListener(e -> SmartFolderEditor.show(this, ctx, SmartFolder.Scope.MAIL,
                null, List.of(), id -> switchFolder(id == 0 ? EmailMessage.FOLDER_INBOX : WorkspaceFolderRail.smartKey(id))));
        folders.setFolderEditor(folder -> SmartFolderEditor.show(this, ctx, SmartFolder.Scope.MAIL,
                folder, List.of(), id -> {
                    if (id == 0 && !WorkspaceFolderRail.smartKey(folder.id()).equals(activeFolder)) {
                        refreshData();
                    } else {
                        switchFolder(id == 0 ? EmailMessage.FOLDER_INBOX : WorkspaceFolderRail.smartKey(id));
                    }
                }));
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
            if (!e.getValueIsAdjusting() && !updatingMailbox) {
                renderSelectedEmail();
            }
        });
        listTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (javax.swing.SwingUtilities.isLeftMouseButton(event) && listTable.rowAtPoint(event.getPoint()) >= 0) {
                    renderSelectedEmail();
                }
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
        int r = TableSorting.selectedModelRow(listTable);
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
        if (!updatingMailbox && !m.read() && EmailMessage.FOLDER_INBOX.equalsIgnoreCase(m.folder())) {
            ctx.getEmailService().markRead(m.id(), true);
            int row = TableSorting.selectedModelRow(listTable);
            var updated = new ArrayList<>(currentEmails);
            updated.set(row, m.withRead(true));
            currentEmails = List.copyOf(updated);
            listModel.setValueAt("✓", row, 0);
            refreshFolderRail(ctx.getSession().requireCurrentUser().id());
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
        EmailComposeDialog.show(this, ctx, initialTo, initialCc, initialSubject, initialBody, null, this::refreshData);
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
        switchFolder(EmailMessage.FOLDER_INBOX);
        JOptionPane.showMessageDialog(this, i18n.t("email.sim_received"),
                i18n.t("common.success"), JOptionPane.INFORMATION_MESSAGE);
    }

    private SmartFolder currentSmartFolder() {
        return smartFolders.stream().filter(folder -> WorkspaceFolderRail.smartKey(folder.id()).equals(activeFolder))
                .findFirst().orElse(null);
    }

    private void refreshFolderRail(long userId) {
        var i18n = ctx.getI18n();
        int unread = ctx.getEmailService().countUnreadInbox(userId);
        var inbox = i18n.t("email.folder.inbox").toUpperCase(Locale.ROOT) + (unread == 0 ? "" : "  [" + unread + "]");
        folders.setEntries(List.of(
                new WorkspaceFolderRail.Entry(EmailMessage.FOLDER_INBOX, inbox, SidebarIcons.mailInbox(16)),
                new WorkspaceFolderRail.Entry(EmailMessage.FOLDER_SENT, i18n.t("email.folder.sent").toUpperCase(Locale.ROOT), SidebarIcons.mailSent(16)),
                new WorkspaceFolderRail.Entry(EmailMessage.FOLDER_DRAFTS, i18n.t("email.folder.drafts").toUpperCase(Locale.ROOT), SidebarIcons.mailDrafts(16)),
                new WorkspaceFolderRail.Entry(EmailMessage.FOLDER_TRASH, i18n.t("email.folder.trash").toUpperCase(Locale.ROOT), SidebarIcons.mailTrash(16))
        ), smartFolders, activeFolder);
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
        updatingMailbox = true;
        try {
            loadMailbox();
        } finally {
            updatingMailbox = false;
        }
    }

    private void loadMailbox() {
        User me = ctx.getSession().requireCurrentUser();
        simIncomingBtn.setVisible(ctx.getSession().canUseExternalEmail());
        smartFolders = ctx.getSmartFolderDao().find(me.id(), SmartFolder.Scope.MAIL);
        SmartFolder smart = currentSmartFolder();
        if (smart == null && !MAILBOXES.contains(activeFolder)) {
            activeFolder = EmailMessage.FOLDER_INBOX;
        }
        refreshFolderRail(me.id());

        EmailMessage previous = getSelectedEmail();
        currentEmails = smart == null ? ctx.getEmailService().listFolder(me.id(), activeFolder)
                : ctx.getEmailService().listFolders(me.id(), smart.rules().getOrDefault(SmartFolder.Field.MAILBOX,
                        List.of(EmailMessage.FOLDER_INBOX, EmailMessage.FOLDER_SENT)))
                        .stream().filter(smart::matches).toList();
        listModel.setRowCount(0);
        int selectedIndex = 0;
        for (int index = 0; index < currentEmails.size(); index++) {
            EmailMessage m = currentEmails.get(index);
            if (previous != null && m.id() == previous.id()) selectedIndex = index;
            String peer = EmailMessage.FOLDER_SENT.equals(m.folder()) || EmailMessage.FOLDER_DRAFTS.equals(m.folder())
                    ? m.toAddresses() : m.fromAddress();
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
            TableSorting.selectModelRow(listTable, selectedIndex);
        }
        renderSelectedEmail();
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
        WorkspaceFolderRail.labelIconButton(smartFoldersButton, i18n.t("smart.manage"));
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
        ctx.getSession().getCurrentUser().ifPresent(user -> refreshFolderRail(user.id()));
        applyTheme();
    }

    @Override
    public void applyTheme() {
        folders.render();
        BrutalistStyle.styleSecondaryButtons(replyBtn, forwardBtn, toggleReadBtn, deleteBtn,
                simIncomingBtn, openAttachmentBtn);
        BrutalistStyle.applyBoxCard(readerCard, 10, 12);
        BrutalistStyle.styleTable(listTable);
        readerBodyArea.setBackground(ThemeManager.bg());
        readerBodyArea.setForeground(ThemeManager.fg());
    }
}
