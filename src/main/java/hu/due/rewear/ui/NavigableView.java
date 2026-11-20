package hu.due.rewear.ui;

import javax.swing.JComponent;

/**
 * Contract for modular workspace screens hosted inside {@link MainFrame}.
 */
public interface NavigableView {

    String getViewKey();

    String getTitleI18nKey();

    JComponent getComponent();

    default void refreshData() {
        // Optional hook called when the user navigates to this view.
    }

    void applyTranslations();
}
