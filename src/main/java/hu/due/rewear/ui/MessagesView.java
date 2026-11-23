package hu.due.rewear.ui;

import hu.due.rewear.config.AppContext;
import hu.due.rewear.config.ThemeManager;
import hu.due.rewear.i18n.I18nManager;
import hu.due.rewear.model.ChatMessage;
import hu.due.rewear.model.ChatRoom;
import hu.due.rewear.model.User;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Element/Matrix-inspired Brutalist Monochrome internal company messaging workspace
 * with group rooms ({@code #általános}, {@code #raktár-logisztika}, custom rooms)
 * and 1-on-1 direct messages between teammates.
 */
public final class MessagesView extends JPanel implements NavigableView {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final AppContext ctx;

    private final JLabel roomsHeader = new JLabel();
    private final JButton newRoomBtn = new JButton();
    private final DefaultListModel<RoomEntry> roomsModel = new DefaultListModel<>();
    private final JList<RoomEntry> roomsList = new JList<>(roomsModel);

    private final JLabel dmHeader = new JLabel();
    private final JButton newDmBtn = new JButton();
    private final DefaultListModel<RoomEntry> dmModel = new DefaultListModel<>();
    private final JList<RoomEntry> dmList = new JList<>(dmModel);

    private final JPanel chatPane = new JPanel(new BorderLayout(0, 8));
    private final JLabel activeRoomTitle = new JLabel("—");
    private final JLabel activeRoomTopic = new JLabel(" ");
    private final JButton addMemberBtn = new JButton();
    private final JButton refreshBtn = new JButton();
    private final JTextArea feedArea = new JTextArea();
    private final JTextField messageInput = new JTextField();
    private final JButton sendBtn = new JButton();

    private ChatRoom selectedRoom;
    private boolean updatingSelection = false;

    private record RoomEntry(ChatRoom room, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    public MessagesView(AppContext ctx) {
        this.ctx = ctx;
        setLayout(new BorderLayout(12, 10));
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        buildUi();
        wireEvents();
        applyTranslations();
        applyTheme();
    }

    private void buildUi() {
        // Left Element-style rooms & DMs rail
        JPanel leftRail = new JPanel();
        leftRail.setLayout(new BoxLayout(leftRail, BoxLayout.Y_AXIS));
        leftRail.setPreferredSize(new Dimension(260, 0));
        BrutalistStyle.applyBoxCard(leftRail, 10, 10);

        JPanel roomsHeadBar = new JPanel(new BorderLayout(6, 0));
        roomsHeadBar.setOpaque(false);
        roomsHeadBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        roomsHeader.setFont(ThemeManager.monoFont(Font.BOLD, 12f));
        roomsHeadBar.add(roomsHeader, BorderLayout.WEST);
        roomsHeadBar.add(newRoomBtn, BorderLayout.EAST);
        leftRail.add(roomsHeadBar);
        leftRail.add(Box.createVerticalStrut(6));

        roomsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        roomsList.setFont(ThemeManager.monoFont(Font.BOLD, 12f));
        JScrollPane roomsScroll = new JScrollPane(roomsList);
        roomsScroll.setPreferredSize(new Dimension(236, 220));
        leftRail.add(roomsScroll);
        leftRail.add(Box.createVerticalStrut(12));

        JPanel dmHeadBar = new JPanel(new BorderLayout(6, 0));
        dmHeadBar.setOpaque(false);
        dmHeadBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        dmHeader.setFont(ThemeManager.monoFont(Font.BOLD, 12f));
        dmHeadBar.add(dmHeader, BorderLayout.WEST);
        dmHeadBar.add(newDmBtn, BorderLayout.EAST);
        leftRail.add(dmHeadBar);
        leftRail.add(Box.createVerticalStrut(6));

        dmList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        dmList.setFont(ThemeManager.monoFont(Font.PLAIN, 12f));
        JScrollPane dmScroll = new JScrollPane(dmList);
        dmScroll.setPreferredSize(new Dimension(236, 220));
        leftRail.add(dmScroll);

        add(leftRail, BorderLayout.WEST);

        // Right chat workspace
        JPanel chatHeader = new JPanel(new BorderLayout(12, 2));
        chatHeader.setOpaque(false);
        JPanel titleStack = new JPanel(new BorderLayout(0, 2));
        titleStack.setOpaque(false);
        activeRoomTitle.setFont(ThemeManager.monoFont(Font.BOLD, 15f));
        activeRoomTopic.setFont(ThemeManager.monoFont(Font.PLAIN, 11.5f));
        titleStack.add(activeRoomTitle, BorderLayout.NORTH);
        titleStack.add(activeRoomTopic, BorderLayout.SOUTH);
        chatHeader.add(titleStack, BorderLayout.CENTER);

        JPanel headerBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        headerBtns.setOpaque(false);
        headerBtns.add(addMemberBtn);
        headerBtns.add(refreshBtn);
        chatHeader.add(headerBtns, BorderLayout.EAST);
        chatPane.add(chatHeader, BorderLayout.NORTH);

        feedArea.setEditable(false);
        feedArea.setLineWrap(true);
        feedArea.setWrapStyleWord(true);
        feedArea.setFont(ThemeManager.monoFont(Font.PLAIN, 12.5f));
        feedArea.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        chatPane.add(new JScrollPane(feedArea), BorderLayout.CENTER);

        JPanel composer = new JPanel(new BorderLayout(8, 0));
        composer.setOpaque(false);
        messageInput.setFont(ThemeManager.monoFont(Font.PLAIN, 13f));
        composer.add(messageInput, BorderLayout.CENTER);
        composer.add(sendBtn, BorderLayout.EAST);
        chatPane.add(composer, BorderLayout.SOUTH);

        add(chatPane, BorderLayout.CENTER);
    }

    private void wireEvents() {
        roomsList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !updatingSelection) {
                RoomEntry entry = roomsList.getSelectedValue();
                if (entry != null) {
                    updatingSelection = true;
                    dmList.clearSelection();
                    updatingSelection = false;
                    selectRoom(entry.room());
                }
            }
        });
        dmList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !updatingSelection) {
                RoomEntry entry = dmList.getSelectedValue();
                if (entry != null) {
                    updatingSelection = true;
                    roomsList.clearSelection();
                    updatingSelection = false;
                    selectRoom(entry.room());
                }
            }
        });

        newRoomBtn.addActionListener(e -> handleCreateRoom());
        newDmBtn.addActionListener(e -> handleNewDirectMessage());
        addMemberBtn.addActionListener(e -> handleAddMember());
        refreshBtn.addActionListener(e -> refreshData());
        sendBtn.addActionListener(e -> handleSendMessage());
        messageInput.addActionListener(e -> handleSendMessage());
    }

    private void selectRoom(ChatRoom room) {
        this.selectedRoom = room;
        if (room == null) {
            activeRoomTitle.setText("—");
            activeRoomTopic.setText(" ");
            feedArea.setText("");
            addMemberBtn.setEnabled(false);
            return;
        }
        long meId = ctx.getSession().requireCurrentUser().id();
        ctx.getChatService().markRoomRead(room.id(), meId);

        activeRoomTitle.setText(room.name());
        List<Long> memberIds = ctx.getChatService().listMemberIds(room.id());
        activeRoomTopic.setText((room.topic() != null && !room.topic().isBlank() ? room.topic() + "  |  " : "")
                + ctx.getI18n().t("chat.members_count", memberIds.size()));
        addMemberBtn.setVisible(ctx.getSession().canManageChatRooms());
        addMemberBtn.setEnabled(ctx.getSession().canManageChatRooms() && !room.isDirect());

        List<ChatMessage> msgs = ctx.getChatService().listMessages(room.id());
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : msgs) {
            String ts = m.createdAt() != null ? TIME_FMT.format(m.createdAt()) : "";
            String role = ctx.getI18n().t("role." + m.senderRoleCode().name()).toUpperCase(Locale.ROOT);
            sb.append("[").append(ts).append("] ")
                    .append(m.senderName()).append(" (").append(role).append(")\n")
                    .append("  ").append(m.body()).append("\n\n");
        }
        feedArea.setText(sb.toString());
        feedArea.setCaretPosition(feedArea.getDocument().getLength());
    }

    private void handleSendMessage() {
        if (selectedRoom == null) {
            return;
        }
        String text = messageInput.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        try {
            long meId = ctx.getSession().requireCurrentUser().id();
            ctx.getChatService().sendMessage(selectedRoom.id(), meId, text);
            messageInput.setText("");
            selectRoom(selectedRoom);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                    ctx.getI18n().t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleCreateRoom() {
        I18nManager i18n = ctx.getI18n();
        JTextField nameField = new JTextField("#", 22);
        JTextField topicField = new JTextField(22);

        JPanel membersBox = new JPanel();
        membersBox.setLayout(new BoxLayout(membersBox, BoxLayout.Y_AXIS));
        List<User> allUsers = ctx.getUserDao().findAll();
        long meId = ctx.getSession().requireCurrentUser().id();
        List<JCheckBox> boxes = new ArrayList<>();
        List<Long> boxUserIds = new ArrayList<>();
        for (User u : allUsers) {
            if (u.id() == meId) continue;
            boolean autoAdmin = u.roleCode() == hu.due.rewear.model.RoleCode.SUPER_ADMIN
                    || u.roleCode() == hu.due.rewear.model.RoleCode.ADMIN;
            JCheckBox cb = new JCheckBox(u.displayName() + " (" + i18n.t("role." + u.roleCode().name())
                    + (autoAdmin ? " — auto" : "") + ")", true);
            if (autoAdmin) {
                cb.setEnabled(false);
            }
            boxes.add(cb);
            boxUserIds.add(u.id());
            membersBox.add(cb);
        }

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        int r = 0;
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("chat.room_name") + " *", nameField);
        BrutalistStyle.addFormRow(form, gbc, r++, i18n.t("chat.room_topic"), topicField);
        BrutalistStyle.addFormRow(form, gbc, r, i18n.t("chat.members"), new JScrollPane(membersBox));

        if (JOptionPane.showConfirmDialog(this, form, i18n.t("chat.new_room"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        List<Long> chosen = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            if (boxes.get(i).isSelected()) {
                chosen.add(boxUserIds.get(i));
            }
        }
        try {
            long roomId = ctx.getChatService().createRoom(nameField.getText(), topicField.getText(), meId, chosen);
            refreshDataSelecting(roomId);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), i18n.t("common.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void handleNewDirectMessage() {
        I18nManager i18n = ctx.getI18n();
        User me = ctx.getSession().requireCurrentUser();
        List<User> others = ctx.getUserDao().findAll().stream()
                .filter(u -> u.id() != me.id() && u.active())
                .toList();
        if (others.isEmpty()) {
            return;
        }
        JComboBox<User> userCombo = new JComboBox<>(others.toArray(new User[0]));
        userCombo.setRenderer((list, val, idx, sel, foc) -> {
            JLabel l = new JLabel(val != null
                    ? val.displayName() + " — " + i18n.t("role." + val.roleCode().name()) : "");
            l.setOpaque(true);
            l.setBackground(sel ? list.getSelectionBackground() : list.getBackground());
            l.setForeground(sel ? list.getSelectionForeground() : list.getForeground());
            l.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            return l;
        });
        if (JOptionPane.showConfirmDialog(this, userCombo, i18n.t("chat.new_dm"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        User target = (User) userCombo.getSelectedItem();
        if (target == null) return;
        long dmId = ctx.getChatService().getOrCreateDirectMessage(me, target);
        refreshDataSelecting(dmId);
    }

    private void handleAddMember() {
        if (selectedRoom == null || selectedRoom.isDirect()) {
            return;
        }
        I18nManager i18n = ctx.getI18n();
        List<Long> existing = ctx.getChatService().listMemberIds(selectedRoom.id());
        List<User> candidates = ctx.getUserDao().findAll().stream()
                .filter(u -> !existing.contains(u.id()) && u.active())
                .toList();
        if (candidates.isEmpty()) {
            JOptionPane.showMessageDialog(this, i18n.t("chat.all_members_in"),
                    i18n.t("common.info"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JComboBox<User> combo = new JComboBox<>(candidates.toArray(new User[0]));
        combo.setRenderer((list, val, idx, sel, foc) -> {
            JLabel l = new JLabel(val != null ? val.displayName() + " (" + val.username() + ")" : "");
            l.setOpaque(true);
            l.setBackground(sel ? list.getSelectionBackground() : list.getBackground());
            l.setForeground(sel ? list.getSelectionForeground() : list.getForeground());
            l.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            return l;
        });
        if (JOptionPane.showConfirmDialog(this, combo, i18n.t("chat.add_member"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION
                && combo.getSelectedItem() instanceof User u) {
            ctx.getChatService().addMemberToRoom(selectedRoom.id(), u.id());
            selectRoom(selectedRoom);
        }
    }

    private void refreshDataSelecting(long roomIdToSelect) {
        boolean canManageRooms = ctx.getSession().canManageChatRooms();
        newRoomBtn.setVisible(canManageRooms);
        addMemberBtn.setVisible(canManageRooms);

        long meId = ctx.getSession().requireCurrentUser().id();
        List<ChatRoom> all = ctx.getChatService().listRoomsForUser(meId);
        updatingSelection = true;
        try {
            roomsModel.clear();
            dmModel.clear();
            int roomSelIdx = -1;
            int dmSelIdx = -1;
            ChatRoom matched = null;

            for (ChatRoom r : all) {
                String lbl = r.name() + (r.unreadCount() > 0 ? "  [" + r.unreadCount() + "]" : "");
                RoomEntry entry = new RoomEntry(r, lbl);
                if (r.isDirect()) {
                    dmModel.addElement(entry);
                    if (r.id() == roomIdToSelect) {
                        dmSelIdx = dmModel.size() - 1;
                        matched = r;
                    }
                } else {
                    roomsModel.addElement(entry);
                    if (r.id() == roomIdToSelect) {
                        roomSelIdx = roomsModel.size() - 1;
                        matched = r;
                    }
                }
            }
            if (roomSelIdx >= 0) {
                roomsList.setSelectedIndex(roomSelIdx);
                dmList.clearSelection();
            } else if (dmSelIdx >= 0) {
                dmList.setSelectedIndex(dmSelIdx);
                roomsList.clearSelection();
            } else if (!roomsModel.isEmpty()) {
                roomsList.setSelectedIndex(0);
                matched = roomsModel.get(0).room();
            } else if (!dmModel.isEmpty()) {
                dmList.setSelectedIndex(0);
                matched = dmModel.get(0).room();
            }
            selectRoom(matched);
        } finally {
            updatingSelection = false;
        }
    }

    @Override
    public String getViewKey() {
        return MainFrame.VIEW_MESSAGES;
    }

    @Override
    public String getTitleI18nKey() {
        return "chat.title";
    }

    @Override
    public JComponent getComponent() {
        return this;
    }

    @Override
    public void refreshData() {
        refreshDataSelecting(selectedRoom != null ? selectedRoom.id() : -1L);
    }

    @Override
    public void applyTranslations() {
        I18nManager i18n = ctx.getI18n();
        roomsHeader.setText(i18n.t("chat.rooms").toUpperCase(Locale.ROOT));
        newRoomBtn.setText("+ " + i18n.t("chat.room_short").toUpperCase(Locale.ROOT));
        dmHeader.setText(i18n.t("chat.direct_messages").toUpperCase(Locale.ROOT));
        newDmBtn.setText("+ DM");
        addMemberBtn.setText("+ " + i18n.t("chat.add_member").toUpperCase(Locale.ROOT));
        refreshBtn.setText(i18n.t("common.refresh").toUpperCase(Locale.ROOT));
        sendBtn.setText(i18n.t("email.send").toUpperCase(Locale.ROOT));
        applyTheme();
    }

    @Override
    public void applyTheme() {
        BrutalistStyle.stylePrimaryButton(sendBtn);
        BrutalistStyle.styleSecondaryButtons(newRoomBtn, newDmBtn, addMemberBtn, refreshBtn);
        BrutalistStyle.applyBoxCard(chatPane, 10, 12);
        feedArea.setBackground(ThemeManager.bg());
        feedArea.setForeground(ThemeManager.fg());
        roomsList.setBackground(ThemeManager.bg());
        roomsList.setForeground(ThemeManager.fg());
        dmList.setBackground(ThemeManager.bg());
        dmList.setForeground(ThemeManager.fg());
    }
}
