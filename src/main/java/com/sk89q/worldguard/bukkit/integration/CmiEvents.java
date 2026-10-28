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
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nullable;

final class CmiEvents {

    static void register(Plugin owner, Listener listener, ActionHandler handler) {
        register(owner, listener, CMIBackpackOpenEvent.class,
                (ignored, event) -> {
                    if (event instanceof CMIBackpackOpenEvent backpackEvent) {
                        handler.handle(CmiIntegration.Action.BACKPACK, backpackEvent, backpackEvent.getPlayer(), null);
                    }
                });
        register(owner, listener, CMIPortalUseEvent.class,
                (ignored, event) -> {
                    if (event instanceof CMIPortalUseEvent portalEvent) {
                        handler.handle(CmiIntegration.Action.PORTAL, portalEvent, portalEvent.getPlayer(), null);
                    }
                });
        register(owner, listener, CMIPlayerOpenArmorStandEditorEvent.class,
                (ignored, event) -> {
                    if (event instanceof CMIPlayerOpenArmorStandEditorEvent editorEvent) {
                        handler.handle(CmiIntegration.Action.ARMORSTAND_EDIT, editorEvent,
                                editorEvent.getPlayer(), editorEvent.getArmorStand());
                    }
                });
    }

    private static void register(Plugin owner, Listener listener, Class<? extends Event> type, EventExecutor handler) {
        owner.getServer().getPluginManager().registerEvent(type, listener, EventPriority.HIGH,
                handler, owner, true);
    }

    interface ActionHandler {
        void handle(CmiIntegration.Action action, Cancellable event, Player player, @Nullable ArmorStand target);
    }
}
