package net.milenco.buddy.mixin.client;

import java.util.List;

import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The book and quill being written: which hand holds it and its pages, for the panel's book editor (Screens). */
@Mixin(BookEditScreen.class)
public interface BookEditAccessor {
	@Accessor("hand") InteractionHand buddy$hand();
	@Accessor("pages") List<String> buddy$pages();
}
