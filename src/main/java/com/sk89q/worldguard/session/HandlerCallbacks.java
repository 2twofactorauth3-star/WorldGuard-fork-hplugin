package com.sk89q.worldguard.session;

import com.sk89q.worldedit.util.Location;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.session.handler.FeedFlag;
import com.sk89q.worldguard.session.handler.FlagValueChangeHandler;
import com.sk89q.worldguard.session.handler.Handler;
import com.sk89q.worldguard.session.handler.HealFlag;
import org.jspecify.annotations.NonNull;

import java.util.Set;

final class HandlerCallbacks {
    private static final ClassValue<HandlerCallbacks> TYPES = new ClassValue<>() {
        @Override
        protected HandlerCallbacks computeValue(@NonNull Class<?> type) {
            return new HandlerCallbacks(type);
        }
    };

    public final boolean movement;
    public final boolean boundary;
    public final boolean continuous;
    public final boolean tick;
    public final boolean customTick;
    public final boolean invincibility;

    private HandlerCallbacks(Class<?> type) {
        movement = overrides(type, "testMoveTo", LocalPlayer.class, Location.class, Location.class,
                ApplicableRegionSet.class, MoveType.class);
        Class<?> boundaryType = declaringClass(type, "onCrossBoundary", LocalPlayer.class, Location.class,
                Location.class, ApplicableRegionSet.class, Set.class, Set.class, MoveType.class);
        boundary = boundaryType != Handler.class;
        continuous = boundary && boundaryType != FlagValueChangeHandler.class;
        tick = overrides(type, "tick", LocalPlayer.class, ApplicableRegionSet.class);
        customTick = tick && type != HealFlag.class && type != FeedFlag.class;
        invincibility = overrides(type, "getInvincibility", LocalPlayer.class);
    }

    static HandlerCallbacks of(Handler handler) {
        return TYPES.get(handler.getClass());
    }

    private static boolean overrides(Class<?> type, String name, Class<?>... parameters) {
        return declaringClass(type, name, parameters) != Handler.class;
    }

    private static Class<?> declaringClass(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters).getDeclaringClass();
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
