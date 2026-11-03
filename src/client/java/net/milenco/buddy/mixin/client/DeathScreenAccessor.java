package net.milenco.buddy.mixin.client;

import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The cause of death on the death screen ("... was slain by ..."): the server does not always put it in the chat. */
@Mixin(DeathScreen.class)
public interface DeathScreenAccessor {
	@Accessor("causeOfDeath") Component buddy$causeOfDeath();
}
