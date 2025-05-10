package com.npcdropnotifier;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Varbits;
import net.runelite.api.WidgetNode;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetModalMode;
import net.runelite.client.callback.ClientThread;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class NpcDropNotifierPopup {
    @Inject private Client client;
    @Inject private ClientThread clientThread;


    private WidgetNode popupWidgetNode;
    private final List<String> queuedPopups = new ArrayList<>();


    public void addNotificationToQueue(String message) {
        String cleanMessage = message.replace("~", "").replace("|", "");
        queuedPopups.add(cleanMessage);
        if (queuedPopups.size() == 1) {
            showPopup(cleanMessage);
        }
    }
    public static final int RESIZABLE_CLASSIC_LAYOUT = (161 << 16) | 13;
    public static final int RESIZABLE_MODERN_LAYOUT = (164 << 16) | 13;
    public static final int FIXED_CLASSIC_LAYOUT = 35913770;

    private void showPopup(String message) {
        clientThread.invokeLater(() -> {
            try {
                int componentId = client.isResized()
                        ? client.getVarbitValue(Varbits.SIDE_PANELS) == 1
                        ? RESIZABLE_MODERN_LAYOUT
                        : RESIZABLE_CLASSIC_LAYOUT
                        : FIXED_CLASSIC_LAYOUT;

                popupWidgetNode = client.openInterface(componentId, 660, WidgetModalMode.MODAL_CLICKTHROUGH);
                client.runScript(3343, "New Monster Drop", message, -1);

                clientThread.invokeLater(this::tryClearMessage);
            } catch (IllegalStateException ex) {
                log.debug("Failed to show popup");
                clientThread.invokeLater(this::tryClearMessage);
            }
        });
    }

    private boolean tryClearMessage() {
        Widget w = client.getWidget(660, 1);

        if (w != null && w.getWidth() > 0) {
            return false;
        }

        try {
            client.closeInterface(popupWidgetNode, true);
        } catch (Exception ex) {
            log.debug("Failed to clear message");
        }
        popupWidgetNode = null;
        queuedPopups.remove(0);

        if (!queuedPopups.isEmpty()) {
            clientThread.invokeLater(() -> {
                showPopup(queuedPopups.get(0));
                return true;
            });
        }
        return true;
    }
}
