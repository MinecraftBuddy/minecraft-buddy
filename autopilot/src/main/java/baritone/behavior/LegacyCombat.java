/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

// pre 1.9 combat: no cooldown, spam click, swing packet before the hit. every melee swing goes through hit() so the packet
// order lives in one place, and the callers that pace themselves off the cooldown ask active() for the clicker instead
public final class LegacyCombat {

    private LegacyCombat() {
    }

    // 26.3 only ever talks to 26.3 servers, so never. version-agnostic swaps this one line for the portal's server version
    // check, keep it alone
    public static boolean active() {
        return false;
    }

    // a 1.8 client sends the arm swing and then the attack, and old anticheats want to see a swing before every hit.
    // ViaFabricPlus only fixes the order inside Minecraft#startAttack and we never go through that
    public static void hit(MultiPlayerGameMode gameMode, LocalPlayer player, Entity target, InteractionHand hand) {
        if (active()) {
            swing(player, hand);
            gameMode.attack(player, target);
        } else {
            gameMode.attack(player, target);
            swing(player, hand);
        }
    }

    // the swing of a hit or a dig as Minecraft#startAttack and #continueAttack make it in 26.3: the animation is the client's
    // own, and the server hears of the swing from the punch packet alone (26.3 has no swing packet). LivingEntity#swing on
    // its own sent nothing, so every hit and every dig was an attack without a swing to an anti-cheat: Vulcan's Bad Packets
    // 7 and 9, "attacked=true swung=false" (the buddy's fork, 3 Nov 2026). placing a block or using an item sends no punch,
    // in vanilla either
    public static void swing(LocalPlayer player, InteractionHand hand) {
        player.swing(hand, player.getItemInHand(hand).getAttackAnimation(), false);
        player.connection.send(ServerboundPunchPacket.INSTANCE);
    }
}
