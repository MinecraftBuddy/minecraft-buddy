/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior.trip;

import baritone.api.utils.Helper;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import static baritone.api.command.IBaritoneChatControl.FORCE_COMMAND_PREFIX;

// the question itself. every chat component the feature builds lives in here, so a version port only fights one file
final class TripPrompt {

    private TripPrompt() {
    }

    static String question(GatherPlan plan) {
        if (plan.kind() == Trip.Ask.STUCK) {
            return "I can't finish this path without blocks, may I collect some so I can continue?";
        }
        String needs = plan.pickaxe() && plan.blocks() > 0 ? "mining and placing blocks" : plan.pickaxe() ? "mining" : "placing blocks";
        String get = plan.pickaxe() && plan.blocks() > 0 ? "collect blocks and make a pickaxe" : plan.pickaxe() ? "make a pickaxe" : "collect blocks";
        return "I found a faster path, but it needs " + needs + ". May I " + get + " so I can take it?";
    }

    static String detail(GatherPlan plan, double savedTicks) {
        String what = plan.describe();
        if (plan.kind() == Trip.Ask.FASTER && savedTicks > 0) {
            return "(" + what + ", saves about " + duration(savedTicks) + ")";
        }
        return "(" + what + ")";
    }

    // 1m 20s. nobody wants to read 1600 ticks
    static String duration(double ticks) {
        long s = Math.max(0, Math.round(ticks / 20));
        return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
    }

    static void ask(long tripId, GatherPlan plan, double savedTicks) {
        MutableComponent msg = Component.literal(question(plan) + " " + detail(plan, savedTicks) + " ");
        msg.append(button("[Yes]", ChatFormatting.GREEN, "gather yes " + tripId, "Go and get " + plan.describe()));
        msg.append(Component.literal(" "));
        msg.append(button("[No]", ChatFormatting.RED, "gather no " + tripId, "Don't, for this whole trip"));
        // always chat, never just a toast: a toast can't be clicked. the toast is the "hey, look at chat"
        Helper.HELPER.logDirect(false, msg);
        Helper.HELPER.logToast(Helper.getPrefix(), Component.literal(plan.kind() == Trip.Ask.STUCK
                ? "Can't finish the path without blocks. Answer in chat"
                : "Found a faster path that needs blocks. Answer in chat"));
    }

    private static MutableComponent button(String text, ChatFormatting color, String command, String hover) {
        MutableComponent c = Component.literal(text);
        c.setStyle(c.getStyle()
                .withColor(color)
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover)))
                .withClickEvent(new ClickEvent.RunCommand(FORCE_COMMAND_PREFIX + command)));
        return c;
    }

    static void say(String message) {
        Helper.HELPER.logDirect(message);
    }
}
