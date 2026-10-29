package com.sk89q.worldguard.session.handler;

import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.protection.flags.StateFlag.State;
import com.sk89q.worldguard.session.Session;

import javax.annotation.Nullable;

public class GodMode extends Handler {
    public static final Factory FACTORY = new Factory();

    public static class Factory extends Handler.Factory<GodMode> {
        @Override
        public GodMode create(Session session) {
            return new GodMode(session);
        }
    }

    private boolean godMode;

    public GodMode(Session session) {
        super(session);
    }

    public boolean hasGodMode(LocalPlayer player) {
        return godMode;
    }

    public void setGodMode(LocalPlayer player, boolean enabled) {
        godMode = enabled;
    }

    @Override
    @Nullable
    public State getInvincibility(LocalPlayer player) {
        return hasGodMode(player) ? State.ALLOW : null;
    }

    public static boolean set(LocalPlayer player, Session session, boolean value) {
        GodMode handler = session.getHandler(GodMode.class);
        if (handler == null) {
            handler = new GodMode(session);
            session.register(handler);
        }
        handler.setGodMode(player, value);
        return true;
    }
}
