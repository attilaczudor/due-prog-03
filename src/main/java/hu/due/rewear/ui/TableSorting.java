package hu.due.rewear.ui;

import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Graphics;
import java.math.BigDecimal;
import java.text.Collator;
import java.util.Comparator;
import java.util.regex.Pattern;

final class TableSorting {
    private static final Pattern NUMBER = Pattern.compile(
            "([+−-]?(?:\\d{1,3}(?:[ \\u00A0\\u202F]\\d{3})+|\\d+)(?:[.,]\\d+)?)\\s*(%|\\p{L}+(?:/\\p{L}+)?)?");
    private static final Icon ASCENDING = new SortArrows(SortOrder.ASCENDING);
    private static final Icon DESCENDING = new SortArrows(SortOrder.DESCENDING);
    private static final Icon UNSORTED = new SortArrows(SortOrder.UNSORTED);

    private TableSorting() { }

    static void install(JTable table) {
        if (table.getRowSorter() == null) {
            table.setRowSorter(new DisplayValueSorter(table.getModel()));
        }
        var header = table.getTableHeader();
        if (!(header.getDefaultRenderer() instanceof SortHeader)) {
            header.setDefaultRenderer(new SortHeader(header.getDefaultRenderer()));
        }
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    static int selectedModelRow(JTable table) {
        int selected = table.getSelectedRow();
        return selected < 0 ? -1 : table.convertRowIndexToModel(selected);
    }

    static void selectModelRow(JTable table, int modelRow) {
        int viewRow = table.convertRowIndexToView(modelRow);
        if (viewRow >= 0) table.setRowSelectionInterval(viewRow, viewRow);
    }

    private static final class DisplayValueSorter extends TableRowSorter<TableModel> {
        private final Collator textOrder = Collator.getInstance();

        DisplayValueSorter(TableModel model) {
            super(model);
        }

        @Override
        public Comparator<?> getComparator(int column) {
            Class<?> type = getModel().getColumnClass(column);
            return type == String.class || type == Object.class ? this::compareDisplayValues : super.getComparator(column);
        }

        private int compareDisplayValues(Object left, Object right) {
            String leftText = left.toString();
            String rightText = right.toString();
            var a = NUMBER.matcher(leftText.strip());
            var b = NUMBER.matcher(rightText.strip());
            boolean numericA = a.matches();
            boolean numericB = b.matches();
            if (!numericA || !numericB) {
                if (numericA != numericB) return numericA ? -1 : 1;
                return textOrder.compare(leftText, rightText);
            }
            String unitA = a.group(2) == null ? "" : a.group(2);
            String unitB = b.group(2) == null ? "" : b.group(2);
            int units = unitA.compareToIgnoreCase(unitB);
            return units != 0 ? units : decimal(a.group(1)).compareTo(decimal(b.group(1)));
        }

        private static BigDecimal decimal(String value) {
            return new BigDecimal(value.replace(" ", "").replace("\u00a0", "").replace("\u202f", "")
                    .replace('−', '-').replace(',', '.'));
        }
    }

    private record SortHeader(TableCellRenderer delegate) implements TableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            Component component = delegate.getTableCellRendererComponent(table, value, selected, focused, row, column);
            if (component instanceof JLabel label) {
                int modelColumn = table.convertColumnIndexToModel(column);
                SortOrder sort = table.getRowSorter().getSortKeys().stream()
                        .filter(key -> key.getColumn() == modelColumn).findFirst().map(RowSorter.SortKey::getSortOrder)
                        .orElse(SortOrder.UNSORTED);
                label.setIcon(switch (sort) {
                    case ASCENDING -> ASCENDING;
                    case DESCENDING -> DESCENDING;
                    case UNSORTED -> UNSORTED;
                });
                label.setHorizontalTextPosition(SwingConstants.LEADING);
                label.setIconTextGap(4);
                label.setToolTipText(value == null ? null : value.toString());
            }
            return component;
        }
    }

    private record SortArrows(SortOrder order) implements Icon {
        @Override public int getIconWidth() { return 9; }
        @Override public int getIconHeight() { return 12; }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            graphics.setColor(component.getForeground());
            if (order != SortOrder.DESCENDING) {
                int top = y + (order == SortOrder.UNSORTED ? 1 : 4);
                graphics.drawPolyline(new int[]{x + 1, x + 4, x + 7}, new int[]{top + 3, top, top + 3}, 3);
            }
            if (order != SortOrder.ASCENDING) {
                int top = y + (order == SortOrder.UNSORTED ? 7 : 4);
                graphics.drawPolyline(new int[]{x + 1, x + 4, x + 7}, new int[]{top, top + 3, top}, 3);
            }
        }
    }
}
