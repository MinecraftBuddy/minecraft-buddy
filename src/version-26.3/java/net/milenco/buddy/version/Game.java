package net.milenco.buddy.version;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

/** Loose game calls that are named differently per Minecraft version. */
public final class Game {
	private Game() {}

	/** Put a line in your own chat; never goes to the server. 1.21.11 calls this displayClientMessage. */
	public static void message(LocalPlayer player, Component text) { player.sendSystemMessage(text); }

	/** The screen that is open (chat, chest, Esc menu), or null. In 1.21.11 that lives on Minecraft itself. */
	public static Screen openScreen(Minecraft mc) { return mc.gui == null ? null : mc.gui.screen(); }

	/** Swap a menu slot with a hotbar slot (like pressing 1-9 above a slot). */
	public static void swapWithHotbar(MultiPlayerGameMode gameMode, int menuId, int slot, int hotbar, Player player) {
		gameMode.handleContainerInput(menuId, slot, hotbar, ContainerInput.SWAP, player);
	}

	/** Ground a sapling may stand on: dirt, coarse dirt, grass, podzol, mud, moss, farmland, ... */
	public static boolean plantable(BlockState ground) { return ground.is(BlockTags.SUPPORTS_VEGETATION); }

	/** Shift-click: move the slot to the other side. */
	public static void quickMove(MultiPlayerGameMode gameMode, int menuId, int slot, Player player) {
		gameMode.handleContainerInput(menuId, slot, 0, ContainerInput.QUICK_MOVE, player);
	}

	/** Plain left click on a slot, like a button in a server menu (a home in /homes). */
	public static void click(MultiPlayerGameMode gameMode, int menuId, int slot, Player player) {
		gameMode.handleContainerInput(menuId, slot, 0, ContainerInput.PICKUP, player);
	}

	/** Throw the whole stack out of this slot (button 1 = the whole stack, not one item). */
	public static void drop(MultiPlayerGameMode gameMode, int menuId, int slot, Player player) {
		gameMode.handleContainerInput(menuId, slot, 1, ContainerInput.THROW, player);
	}

	/** Left-drag the stack on the cursor over these slots: it is shared out evenly, the rest stays on the cursor. */
	public static void spread(MultiPlayerGameMode gameMode, int menuId, int[] slots, Player player) {
		gameMode.handleContainerInput(menuId, -999, 0, ContainerInput.QUICK_CRAFT, player);
		for (int slot : slots) gameMode.handleContainerInput(menuId, slot, 1, ContainerInput.QUICK_CRAFT, player);
		gameMode.handleContainerInput(menuId, -999, 2, ContainerInput.QUICK_CRAFT, player);
	}

	/** Right-click an entity the crosshair is on, as vanilla does it (one call from 26.2 on). */
	public static net.minecraft.world.InteractionResult interactEntity(MultiPlayerGameMode gameMode, Player player, net.minecraft.world.entity.Entity entity,
			net.minecraft.world.phys.EntityHitResult hit, net.minecraft.world.InteractionHand hand) {
		return gameMode.interact(player, entity, hit, hand);
	}

	/** A click with the chosen mouse button (0 left, 1 right), with shift held or not: some menu buttons tell them apart. */
	public static void click(MultiPlayerGameMode gameMode, int menuId, int slot, int button, boolean shift, Player player) {
		gameMode.handleContainerInput(menuId, slot, button, shift ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP, player);
	}

	/**
	 * One click in a menu as the player makes it: a kind of {@link ContainerInput} by its name (PICKUP, QUICK_MOVE, SWAP,
	 * THROW, PICKUP_ALL, CLONE, QUICK_CRAFT) with the button the game gives it. Null when the name is no kind.
	 */
	public static boolean input(MultiPlayerGameMode gameMode, int menuId, int slot, int button, String kind, Player player) {
		ContainerInput in;
		try { in = ContainerInput.valueOf(kind); } catch (IllegalArgumentException e) { return false; }
		gameMode.handleContainerInput(menuId, slot, button, in, player);
		return true;
	}

	/** What a click packet says was done: PICKUP, QUICK_MOVE, SWAP, ... The packet names it differently per version. */
	public static String clickKind(net.minecraft.network.protocol.game.ServerboundContainerClickPacket packet) { return packet.containerInput().name(); }

	/**
	 * The swing that goes with a hit, a miss or a tick of breaking. Since 26.3 swing() only moves the arm on your own
	 * screen and the server hears of it through the punch packet vanilla sends right after: without it a hit lands
	 * with no arm moving, which no real client does.
	 */
	public static void swingAttack(LocalPlayer player) {
		player.swing(InteractionHand.MAIN_HAND, player.getMainHandItem().getAttackAnimation(), false);
		player.connection.send(ServerboundPunchPacket.INSTANCE);
	}

	/** The swing after a click that did something to a block or an entity. Since 26.3 the server works that one out itself: nothing is sent. */
	public static void swingUse(LocalPlayer player, InteractionHand hand) {
		player.swing(hand, player.getItemInHand(hand).getInteractAnimation(), false);
	}

	/** Whether the arm is in a swing right now. A field before 26.3. */
	public static boolean swinging(LocalPlayer player) { return player.isSwinging(); }

	/** One side of a sign: its colour, its glow, its lines. 26.3 names the side with a slot, before it was a boolean. */
	public static SignText signText(SignBlockEntity sign, boolean front) { return sign.getText(front ? SignTextSlot.FRONT : SignTextSlot.BACK); }

	/** The lines on one side of a sign as they were written, unfiltered. A list in 26.3, an array before. */
	public static Component[] signLines(SignBlockEntity sign, boolean front) { return signText(sign, front).getMessages(false).toArray(Component[]::new); }

	/** Whether a composter takes this item. In 26.3 the item says so itself, before the composter kept a table. */
	public static boolean compostable(ItemStack stack) { return stack.has(DataComponents.COMPOSTABLE); }

	/** Where the sign stands that was just typed on. The packets are records in 26.3 and had getters before. */
	public static BlockPos signPos(ServerboundSignUpdatePacket packet) { return packet.pos(); }

	/** What was typed on that sign, line by line. */
	public static List<String> signTyped(ServerboundSignUpdatePacket packet) { return packet.lines(); }

	/** The block a use packet clicks, with the side and the spot on it. */
	public static BlockHitResult useHit(ServerboundUseItemOnPacket packet) { return packet.hitResult(); }

	/** The hand a use packet clicks with. */
	public static InteractionHand useHand(ServerboundUseItemOnPacket packet) { return packet.hand(); }
}
