package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.kit.Safe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;

// the menu music, ours: one streamed track looping for as long as you sit in the menus. MixinMusicManager asks tick()
// every client tick and vanilla's music manager sits the tick out while we say the menus are ours. the file comes the
// MenuSounds way: our own Sound, streamed off the classpath by MixinSoundBufferLibrary, no sounds.json, no fabric api.
// it's on the music channel, so the Music slider turns it down like any vanilla track
public final class MenuMusic {

    // the file masters around -22 dBFS rms and vanilla's menu tracks end up at -24..-29, so a bit under half way
    static final float MUSIC_VOLUME = 0.55f;
    // linear, per tick, from silence to full and back: 2.5 s in, 1.5 s out
    private static final float FADE_IN = MUSIC_VOLUME / 50f;
    private static final float FADE_OUT = MUSIC_VOLUME / 30f;
    // a track that vanished this soon after play got killed by a sound reload or a device switch mid start. one that
    // ran longer than this had a good run, that one doesn't count against it
    private static final int EARLY_DEATH_TICKS = 100;
    private static final int MAX_FAILURES = 3;
    // play said not started: no sound device yet, or no free channel. a second is plenty between asks
    private static final int RETRY_TICKS = 20;

    private static volatile boolean titleShown;
    // set from the io pool when the ogg wouldn't open. a stream that never attaches leaves its channel sitting there
    // forever (it never reaches stopped, so vanilla never cleans it up), so this is the only way we'd hear about it
    private static volatile boolean streamFailed;
    private static Track track;
    private static int ticks;
    private static int startedAt;
    private static int retryAt;
    private static int failures;

    private MenuMusic() {
    }

    // TitleMenu's first frame with no boot overlay over it. from here on the menus get our music, before it only
    // vanilla's gets held back
    static void markTitleShown() {
        titleShown = true;
    }

    // TitleMenu broke, or a title screen came up vanilla: no speaker button to mute us with, so the music goes back
    // to vanilla too. the next title screen that comes up as ours takes it again
    public static void titleGone() {
        titleShown = false;
    }

    // MenuSounds.open couldn't make a stream out of the file
    static void markStreamFailed() {
        streamFailed = true;
    }

    // the speaker button. the same road a click gui toggle takes, so it lands in settings.txt and #set sees it
    static void toggle() {
        try {
            Result r = new McSettings().toggle("guiMenuMusic");
            if (!r.ok()) {
                Safe.report("menu music toggle", new IllegalStateException(r.message()));
            }
        } catch (Throwable t) {
            Safe.report("menu music toggle", t);
        }
    }

    // whether the menus are ours this tick, MixinMusicManager cancels vanilla's tick on true. while the boot overlay
    // is up we only hold vanilla back (it starts a menu track about 5 s into the load), ours waits for the title so it
    // doesn't fade in under a progress bar. if the title never comes up as ours (no atlases) the overlay going away
    // hands the music back to vanilla
    public static boolean tick(Minecraft mc) {
        ticks++;
        boolean menus = SopranoMenus.enabled() && mc.level == null;
        boolean owned = menus && (titleShown || mc.gui.overlay() != null);
        SoundManager sounds = mc.getSoundManager();
        if (owned && titleShown) {
            keep(sounds, SopranoMenus.music() ? MUSIC_VOLUME : 0f);
        } else {
            letGo(sounds);
        }
        return owned;
    }

    // muting is a target of 0, not a stop: the track keeps running silent and unmuting picks it up where it is
    private static void keep(SoundManager sounds, float target) {
        if (track != null && streamFailed) {
            // the file is broken or missing. take the dead channel out ourselves, nobody else will
            streamFailed = false;
            failures++;
            sounds.stop(track);
            track = null;
        }
        if (track != null) {
            if (!track.isStopped() && sounds.isActive(track)) {
                track.aim(target);
                return;
            }
            // gone without us saying so: a sound reload or a device switch forgets every sound without calling stop.
            // only the quick ones count against the track
            if (!track.isStopped() && ticks - startedAt < EARLY_DEATH_TICKS) {
                failures++;
            }
            track = null;
        }
        // muted with nothing playing: nothing to start, unmuting starts it from the top. three dead starts and the
        // file is broken for this session, no point asking the engine every second
        if (target <= 0f || failures >= MAX_FAILURES || ticks < retryAt) {
            return;
        }
        // a new instance every time, the engine keys its maps on the instance and one can't be played twice
        Track t = new Track(target);
        streamFailed = false;
        if (sounds.play(t) == SoundEngine.PlayResult.NOT_STARTED) {
            retryAt = ticks + RETRY_TICKS;
            return;
        }
        track = t;
        startedAt = ticks;
    }

    // joined a world, or the menus aren't ours anymore: fade out, then the track stops itself
    private static void letGo(SoundManager sounds) {
        if (track == null) {
            return;
        }
        if (track.isStopped() || !sounds.isActive(track)) {
            track = null;
            return;
        }
        track.end();
    }

    // the engine ticks this every client tick and reads the volume back, so the fades live here
    private static final class Track extends AbstractTickableSoundInstance {
        // built with the first Track, not when MenuMusic loads, so TitleMenu's markTitleShown can never trip over them
        private static final Identifier ID = Identifier.fromNamespaceAndPath("soprano", "music/menu");
        private static final SoundEvent EVENT = SoundEvent.createVariableRangeEvent(ID);
        // stream: 85 s of stereo decoded up front would be about 16 MB sitting in the buffer cache. path comes out as
        // soprano:sounds/music/menu.ogg
        private static final Sound SOUND = new Sound(ID, ConstantFloat.of(1f), ConstantFloat.of(1f), 1, Sound.Type.FILE,
                true, false, 16);
        private static final WeighedSoundEvents EVENTS = events();

        private float target;
        private boolean ending;

        Track(float target) {
            super(EVENT, SoundSource.MUSIC, SoundInstance.createUnseededRandom());
            this.target = target;
            // starts silent and fades in, canStartSilent below is what lets the engine play a zero volume sound
            this.volume = 0f;
            this.looping = true;
            this.delay = 0;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.relative = true;
        }

        private static WeighedSoundEvents events() {
            // no subtitle: "music plays" in the subtitles box on the title screen would be silly
            WeighedSoundEvents events = new WeighedSoundEvents(ID, null);
            events.addSound(SOUND);
            return events;
        }

        void aim(float v) {
            target = v;
            ending = false;
        }

        void end() {
            target = 0f;
            ending = true;
        }

        // same swap as MenuSounds.Instance: vanilla would ask the sound registry, which has never heard of us. the
        // sound has to be set here too, getVolume reads it
        @Override
        public WeighedSoundEvents getOrResolve(SoundManager manager) {
            this.soundEvent = EVENTS;
            this.sound = SOUND;
            return EVENTS;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            if (volume < target) {
                volume = Math.min(target, volume + FADE_IN);
            } else if (volume > target) {
                volume = Math.max(target, volume - FADE_OUT);
            }
            if (ending && volume <= 0f) {
                stop();
            }
        }
    }
}
