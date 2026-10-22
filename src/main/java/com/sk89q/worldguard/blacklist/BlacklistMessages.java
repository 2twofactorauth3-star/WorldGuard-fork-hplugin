package com.sk89q.worldguard.blacklist;

import com.sk89q.worldedit.util.formatting.text.serializer.plain.PlainComponentSerializer;
import com.sk89q.worldguard.blacklist.event.BlacklistEvent;
import com.sk89q.worldguard.bukkit.BukkitMessages;

public final class BlacklistMessages {

    private BlacklistMessages() {
    }

    public static String message(String key, BlacklistEvent event) {
        return BukkitMessages.template(key, "action", action(event), "target",
                PlainComponentSerializer.INSTANCE.serialize(event.getTarget().getFriendlyNameComponent()));
    }

    public static String action(BlacklistEvent event) {
        return switch (event.getEventType()) {
            case BREAK -> "@wg:blacklistActionBreak@";
            case PLACE -> "@wg:blacklistActionPlace@";
            case INTERACT -> "@wg:blacklistActionInteract@";
            case DISPENSE -> "@wg:blacklistActionDispense@";
            case DESTROY_WITH -> "@wg:blacklistActionDestroyWith@";
            case ACQUIRE -> "@wg:blacklistActionAcquire@";
            case EQUIP -> "@wg:blacklistActionEquip@";
            case DROP -> "@wg:protectionActionDropItems@";
            case USE -> "@wg:blacklistActionUse@";
        };
    }
}
