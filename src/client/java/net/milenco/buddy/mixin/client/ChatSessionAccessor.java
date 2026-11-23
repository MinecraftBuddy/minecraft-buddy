package net.milenco.buddy.mixin.client;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.LocalChatSession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The key the game signs its chat lines with, and when it expires; null without signed chat. */
@Mixin(ClientPacketListener.class)
public interface ChatSessionAccessor {
	@Accessor("chatSession") LocalChatSession buddy$chatSession();
}
