package net.milenco.buddy.mixin.client;

import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The sign being written and its lines, for the panel's sign editor (Screens): the screen sends its lines when it shuts. */
@Mixin(AbstractSignEditScreen.class)
public interface SignEditAccessor {
	@Accessor("sign") SignBlockEntity buddy$sign();
	@Accessor("slot") SignTextSlot buddy$slot();
	@Accessor("messages") String[] buddy$messages();
}
