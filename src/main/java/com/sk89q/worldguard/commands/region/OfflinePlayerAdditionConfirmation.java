/*
 * WorldGuard, a suite of tools for Minecraft
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldGuard team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldguard.commands.region;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

final class OfflinePlayerAdditionConfirmation {

    static final int TIMEOUT_SECONDS = 30;
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

    private final ConcurrentMap<UUID, PendingConfirmation> pending = new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;

    OfflinePlayerAdditionConfirmation() {
        this(System::nanoTime);
    }

    OfflinePlayerAdditionConfirmation(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    boolean confirm(UUID sender, String command) {
        long now = nanoTime.getAsLong();
        AtomicBoolean confirmed = new AtomicBoolean();
        pending.compute(sender, (ignored, current) -> {
            if (current != null && current.command.equals(command) && current.expiresAtNanos >= now) {
                confirmed.set(true);
                return null;
            }
            return new PendingConfirmation(command, now + TIMEOUT_NANOS);
        });
        return confirmed.get();
    }

    void clear(UUID sender) {
        pending.remove(sender);
    }

    private static final class PendingConfirmation {
        public final String command;
        public final long expiresAtNanos;

        private PendingConfirmation(String command, long expiresAtNanos) {
            this.command = command;
            this.expiresAtNanos = expiresAtNanos;
        }
    }
}
