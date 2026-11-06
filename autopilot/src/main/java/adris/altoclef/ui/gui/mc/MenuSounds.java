package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.kit.Safe;
import com.mojang.blaze3d.audio.SoundBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.LoopingAudioStream;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Util;
import net.minecraft.util.valueproviders.ConstantFloat;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

// the title screen's two sounds, both ours (gui-mock/tools/make-sounds.py synthesizes them). fabric only turns a mod's
// assets into a resource pack when fabric api is installed and we don't need fabric api, so there is no sounds.json:
// each play brings its own Sound (Instance below, the sound registry never gets asked) and MixinSoundBufferLibrary
// hands the engine our ogg straight off the classpath. same everywhere, fabric with or without api, forge, neoforge.
// they play on the interface channel, so the "Interface" slider turns them down with vanilla's button click
public final class MenuSounds {

    private static final String NAMESPACE = "soprano";
    private static final Clip HOVER = Clip.of("hover");
    private static final Clip CLICK = Clip.of("click");

    // major pentatonic, any run of it sounds like it meant to. the hover sample is an e5 and the dock starts 7 below,
    // on an a4, so the middle of a normal dock sits on the sample's own pitch
    private static final int[] SCALE = {0, 2, 4, 7, 9, 12, 14, 16, 19};
    // both files peak at -1 dBFS like vanilla's. vanilla plays its click at 0.25, ours sits a touch over it and the
    // hover a touch under, it's the thing that happens fifty times a minute
    private static final float HOVER_VOLUME = 0.22f;
    private static final float CLICK_VOLUME = 0.32f;
    // two notes closer than this are the mouse wobbling on a tile edge, not a sweep along the dock
    private static final long HOVER_GAP_NANOS = 30_000_000L;

    private static long lastHover;

    private MenuSounds() {
    }

    // the pitch for the step'th tile from the left. vanilla clamps pitch to 0.5..2 anyway, the scale tops out at 1.68
    static float note(int step) {
        int i = Math.max(0, Math.min(step, SCALE.length - 1));
        return (float) Math.pow(2.0, (SCALE[i] - 7) / 12.0);
    }

    static void hover(float pitch) {
        long now = System.nanoTime();
        if (now - lastHover < HOVER_GAP_NANOS) {
            return;
        }
        lastHover = now;
        play(Minecraft.getInstance().getSoundManager(), HOVER, pitch, HOVER_VOLUME);
    }

    static void click(SoundManager sounds) {
        play(sounds, CLICK, 1f, CLICK_VOLUME);
    }

    // MixinSoundBufferLibrary asks this before vanilla goes looking in the resource packs
    public static boolean owns(Identifier path) {
        return NAMESPACE.equals(path.getNamespace());
    }

    // what vanilla's getCompleteBuffer does with a resource pack file, done with the mod jar instead. path is the
    // Sound's file path, soprano:sounds/ui/hover.ogg. it goes in vanilla's own buffer cache, so a sound reload or a
    // device switch frees it with everything else and the next play just decodes it again (5 kb, nothing)
    public static CompletableFuture<SoundBuffer> load(Identifier path) {
        String res = "/assets/" + path.getNamespace() + "/" + path.getPath();
        try (InputStream in = MenuSounds.class.getResourceAsStream(res)) {
            if (in == null) {
                throw new FileNotFoundException(res);
            }
            try (JOrbisAudioStream ogg = new JOrbisAudioStream(in)) {
                return CompletableFuture.completedFuture(new SoundBuffer(ogg.readAll(), ogg.getFormat()));
            }
        } catch (Throwable t) {
            Safe.report("title sound load", t);
            return CompletableFuture.failedFuture(t);
        }
    }

    // getStream's twin of load, for MenuMusic's streamed track: vanilla's own lambda with the jar for a resource
    // pack. looping is the engine's call (looping and no delay), it has to be honoured or the track plays once and
    // stops. opened on the io pool like vanilla, not cached, vanilla doesn't cache streams either
    public static CompletableFuture<AudioStream> stream(Identifier path, boolean looping) {
        String res = "/assets/" + path.getNamespace() + "/" + path.getPath();
        return CompletableFuture.supplyAsync(() -> open(res, looping), Util.nonCriticalIoPool());
    }

    private static AudioStream open(String res, boolean looping) {
        InputStream in = MenuSounds.class.getResourceAsStream(res);
        try {
            if (in == null) {
                throw new FileNotFoundException(res);
            }
            return looping ? new LoopingAudioStream(JOrbisAudioStream::new, in) : new JOrbisAudioStream(in);
        } catch (IOException | RuntimeException e) {
            // jorbis throws whatever it likes at a corrupt file, not just io exceptions
            closeQuietly(in);
            MenuMusic.markStreamFailed();
            Safe.report("menu music stream", e);
            throw new CompletionException(e);
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ignored) {
            // it never opened as an ogg, nothing to lose here
        }
    }

    // a sound that can't play is not worth losing the menu over, so this never throws into the draw path
    private static void play(SoundManager sounds, Clip clip, float pitch, float volume) {
        try {
            sounds.play(new Instance(clip, pitch, volume));
        } catch (Throwable t) {
            Safe.report("title sound", t);
        }
    }

    // one sound file and the one-entry event around it, built once. Sound's location is soprano:ui/hover, its path
    // (what the buffer library gets asked for) is soprano:sounds/ui/hover.ogg
    private record Clip(Identifier id, Sound sound, WeighedSoundEvents events) {
        static Clip of(String name) {
            Identifier id = Identifier.fromNamespaceAndPath(NAMESPACE, "ui/" + name);
            Sound sound = new Sound(id, ConstantFloat.of(1f), ConstantFloat.of(1f), 1, Sound.Type.FILE, false, false, 16);
            // no subtitle: a menu blip in the subtitles box would be silly
            WeighedSoundEvents events = new WeighedSoundEvents(id, null);
            events.addSound(sound);
            return new Clip(id, sound, events);
        }
    }

    // forUI with the lookup swapped out: vanilla resolves the id through the sound registry, which has never heard of
    // us. everything after that (volume, pitch, channel, the ui slider) is the vanilla path
    private static final class Instance extends SimpleSoundInstance {
        private final Clip clip;

        Instance(Clip clip, float pitch, float volume) {
            super(clip.id(), SoundSource.UI, volume, pitch, SoundInstance.createUnseededRandom(), false, 0,
                    SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true);
            this.clip = clip;
        }

        @Override
        public WeighedSoundEvents getOrResolve(SoundManager manager) {
            this.soundEvent = clip.events();
            this.sound = clip.sound();
            return clip.events();
        }
    }
}
