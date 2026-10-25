package com.sk89q.worldguard.bukkit.integration;

import com.Zrips.CMI.events.CMIBackpackOpenEvent;
import com.Zrips.CMI.events.CMIPlayerOpenArmorStandEditorEvent;
import com.Zrips.CMI.events.CMIPortalUseEvent;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nullable;
import java.util.function.Consumer;

final class CmiEvents {

    static void register(Plugin owner, Listener listener, ActionHandler handler) {
        register(owner, listener, CMIBackpackOpenEvent.class,
                event -> handler.handle(CmiIntegration.Action.BACKPACK, event, event.getPlayer(), null));
        register(owner, listener, CMIPortalUseEvent.class,
                event -> handler.handle(CmiIntegration.Action.PORTAL, event, event.getPlayer(), null));
        register(owner, listener, CMIPlayerOpenArmorStandEditorEvent.class,
                event -> handler.handle(CmiIntegration.Action.ARMORSTAND_EDIT, event, event.getPlayer(), event.getArmorStand()));
    }

    private static <T extends Event> void register(Plugin owner, Listener listener, Class<T> type, Consumer<T> handler) {
        owner.getServer().getPluginManager().registerEvent(type, listener, EventPriority.HIGH,
                (ignored, event) -> handler.accept(type.cast(event)), owner, true);
    }

    interface ActionHandler {
        void handle(CmiIntegration.Action action, Cancellable event, Player player, @Nullable ArmorStand target);
    }
}
