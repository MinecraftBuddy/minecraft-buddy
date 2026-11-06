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

package baritone.launch.mixins;

import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.mc.MenuMusic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// vanilla's music manager takes the tick off while the menus are ours (MenuMusic decides), so its menu tracks never
// start on top of ours. in game nothing changes. anything of ours that throws means vanilla, music is not worth a crash
@Mixin(MusicManager.class)
public abstract class MixinMusicManager {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    private float currentGain;

    @Unique
    private boolean soprano$owned;

    @Shadow
    public abstract void stopPlaying();

    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true)
    private void soprano$menuMusic(CallbackInfo ci) {
        boolean owned;
        try {
            owned = MenuMusic.tick(minecraft);
        } catch (Throwable t) {
            Safe.report("menu music", t);
            owned = false;
        }
        if (!owned) {
            soprano$owned = false;
            return;
        }
        if (!soprano$owned) {
            soprano$owned = true;
            soprano$takeOver();
        }
        ci.cancel();
    }

    // once per takeover: whatever vanilla had going stops, and the music channel's gain goes back to full. a pale
    // garden fades that gain down to almost nothing and only vanilla's own tick (the one we're cancelling) would ever
    // bring it back up, so leaving one to the title screen would make ours whisper
    @Unique
    private void soprano$takeOver() {
        try {
            stopPlaying();
            currentGain = 1f;
            minecraft.getSoundManager().updateCategoryVolume(SoundSource.MUSIC, 1f);
        } catch (Throwable t) {
            Safe.report("menu music takeover", t);
        }
    }
}
