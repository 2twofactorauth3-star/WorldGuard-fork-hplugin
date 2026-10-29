package com.sk89q.worldguard.session.handler;

import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.session.Session;

public class WaterBreathing extends Handler {
    public static final Factory FACTORY = new Factory();

    public static class Factory extends Handler.Factory<WaterBreathing> {
        @Override
        public WaterBreathing create(Session session) {
            return new WaterBreathing(session);
        }
    }

    public boolean waterBreathing;

    public WaterBreathing(Session session) {
        super(session);
    }

    public boolean hasWaterBreathing() {
        return waterBreathing;
    }

    public void setWaterBreathing(boolean value) {
        waterBreathing = value;
    }

    public static boolean set(LocalPlayer player, Session session, boolean value) {
        WaterBreathing handler = session.getHandler(WaterBreathing.class);
        if (handler == null) {
            handler = new WaterBreathing(session);
            session.register(handler);
        }
        handler.setWaterBreathing(value);
        return true;
    }
}
