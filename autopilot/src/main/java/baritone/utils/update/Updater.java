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

package baritone.utils.update;

import adris.altoclef.BuildInfo;
import adris.altoclef.ui.gui.kit.Safe;
import baritone.api.BaritoneAPI;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

// the title screen's update button. everything slow happens on one daemon thread, the render thread only ever reads
// the volatile snapshot. expected trouble (offline, rate limit, a bad download) lands in the snapshot and nowhere
// else, nobody wants "connection refused" in their log because they played on a plane
public final class Updater {

    private static final String PRETEND = "soprano.update.pretendVersion";
    // the title screen is busy loading for a moment, no point fighting it for a json nobody is looking at yet
    private static final long CHECK_DELAY_MS = 2500;

    private static final Updater INSTANCE = new Updater();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "soprano-updater");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile Snapshot snapshot;

    // everything below is guarded by this and turned into a snapshot by publish()
    private UpdateState state = UpdateState.CHECKING;
    private final String realVersion;
    private final String minecraftVersion;
    private final String pretend;
    private final ModVersion running;
    private SelfJar.Located self = SelfJar.Located.no("still looking for the soprano jar");
    private ReleasePicker.Pick pick;
    // built once per pick, the markdown strip is not something to redo every progress tick
    private Snapshot.Release release;
    // the other track's newest build from the last check, and its card copy. beginSwitch moves it into pick
    private ReleasePicker.Pick switchPick;
    private Snapshot.Release switchRelease;
    // pick is the switch target, not an update. stays on through DOWNLOADING and FAILED so a retry is still a switch
    private boolean switching;
    private long downloaded;
    private long total;
    private String error;
    private boolean unfinished;
    // the last check failed on github's rate limit, and whether the title screen already got its one free retry
    private boolean limited;
    private boolean autoRetried;
    private String stagedName;
    private String finalName;
    private UpdateHttp http;

    private Updater() {
        realVersion = BuildInfo.version();
        minecraftVersion = BuildInfo.minecraftVersion();
        pretend = readPretend();
        running = ModVersion.parse(pretend != null ? pretend : realVersion);
        synchronized (this) {
            publish();
        }
    }

    public static Updater get() {
        return INSTANCE;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    // the check runs once per session no matter how often the title screen opens. a reopen after a failed check gets
    // one more try though, the first one often lands before the wifi is up
    public void checkAsync() {
        checkAsync(true);
    }

    // reopen is false for the resize inits of a title screen that's already up, those shouldn't spend the free retry
    public void checkAsync(boolean reopen) {
        if (!started.compareAndSet(false, true)) {
            boolean again;
            synchronized (this) {
                again = reopen && UpdateState.shouldAutoRetry(state, limited, autoRetried);
                if (again) {
                    autoRetried = true;
                }
            }
            if (again) {
                recheck();
            }
            return;
        }
        boolean network = checkForUpdates();
        synchronized (this) {
            if (running == null || !network) {
                state = UpdateState.DISABLED;
            }
            publish();
        }
        submit(() -> startup(network));
    }

    // false when nothing started (wrong state, can't install), so the caller doesn't act like it did
    public boolean download() {
        synchronized (this) {
            boolean available = state == UpdateState.AVAILABLE || state == UpdateState.FAILED;
            if (!available || !installable()) {
                return false;
            }
            // flipped right here so a double click can't queue two downloads
            state = UpdateState.DOWNLOADING;
            downloaded = 0;
            total = pick.asset().size();
            error = null;
            publish();
        }
        submit(this::runDownload);
        return true;
    }

    // also from DISABLED once checkForUpdates got turned back on, so the card doesn't need a restart for that.
    // turned off counts too: no network at all then, the card just goes back to DISABLED
    public void recheck() {
        boolean enabledNow = checkForUpdates();
        synchronized (this) {
            boolean retryable = state == UpdateState.CHECK_FAILED || state == UpdateState.UP_TO_DATE
                    || (state == UpdateState.DISABLED && started.get());
            if (!retryable || running == null) {
                return;
            }
            // a fresh look forgets the old switch target, the check says again whether there is one
            clearSwitch();
            if (!enabledNow) {
                state = UpdateState.DISABLED;
                error = null;
                publish();
                return;
            }
            state = UpdateState.CHECKING;
            error = null;
            publish();
        }
        submit(this::runCheck);
    }

    // stable <-> nightly from the up to date card. no network, the check already found the build, this just puts it
    // where an update would be so the normal install path (download, verify, stage, swap) does the rest
    public void beginSwitch() {
        synchronized (this) {
            if (state != UpdateState.UP_TO_DATE || switchPick == null) {
                return;
            }
            pick = switchPick;
            release = switchRelease;
            switching = true;
            state = UpdateState.AVAILABLE;
            error = null;
            publish();
        }
    }

    // changed their mind, back to the up to date row with the switch button still on it
    public void cancelSwitch() {
        synchronized (this) {
            boolean cancellable = state == UpdateState.AVAILABLE || state == UpdateState.FAILED;
            if (!switching || !cancellable) {
                return;
            }
            pick = null;
            release = null;
            switching = false;
            state = UpdateState.UP_TO_DATE;
            error = null;
            publish();
        }
    }

    // callers hold the lock. recheck only runs from states where we aren't mid switch, so pick has nothing of it
    private void clearSwitch() {
        switching = false;
        switchPick = null;
        switchRelease = null;
    }

    private void submit(Runnable r) {
        worker.execute(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                // the expected stuff is caught way before this, so this one is worth a line in the log
                Safe.report("updater", t);
                synchronized (Updater.this) {
                    error = "something went wrong in the updater";
                    state = state == UpdateState.DOWNLOADING ? UpdateState.FAILED : UpdateState.CHECK_FAILED;
                    publish();
                }
            }
        });
    }

    private void startup(boolean network) {
        SelfJar.Located found = SelfJar.locate(codeSource(), gameDir(), realVersion);
        synchronized (this) {
            self = found;
            publish();
        }
        if (found.jar() != null && running != null && pretend == null && reconcile(found)) {
            return;
        }
        if (!network || running == null) {
            return;
        }
        try {
            Thread.sleep(CHECK_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        runCheck();
    }

    // true when a staged update from last time is pending, which makes the network check pointless
    private boolean reconcile(SelfJar.Located found) {
        Reconcile.Result r = Reconcile.run(found.jar(), running, minecraftVersion);
        if (r.staged() == null) {
            return false;
        }
        String spawnError = spawnHelper(found.jar(), r.staged(), r.finalJar());
        synchronized (this) {
            stagedName = r.staged().getFileName().toString();
            finalName = r.finalJar().getFileName().toString();
            unfinished = true;
            // no network check after this, so the release is just what the staged jar says it is
            release = new Snapshot.Release(r.version(), null, "", null, 0);
            state = r.stuck() ? UpdateState.STUCK : UpdateState.READY;
            error = spawnError;
            publish();
        }
        return true;
    }

    private void runCheck() {
        ReleasePicker.Result found;
        try {
            found = fetchPick();
        } catch (UpdateHttp.Failed e) {
            checkFailed(e.getMessage(), e.rateLimit);
            return;
        } catch (IOException e) {
            checkFailed("couldn't reach github", false);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            checkFailed("couldn't reach github", false);
            return;
        } catch (RuntimeException e) {
            // gson on a captive portal login page, mostly
            checkFailed("github sent something weird", false);
            return;
        }
        synchronized (this) {
            if (state != UpdateState.CHECKING) {
                // reconcile found a staged jar while this check sat in the queue, that answer beats this one
                return;
            }
            pick = found.pick();
            release = display(pick);
            switching = false;
            switchPick = found.switchTarget();
            switchRelease = display(switchPick);
            state = pick == null ? UpdateState.UP_TO_DATE : UpdateState.AVAILABLE;
            error = null;
            publish();
        }
    }

    private static Snapshot.Release display(ReleasePicker.Pick p) {
        return p == null ? null : new Snapshot.Release(p.version().raw(), p.title(), Markdown.plain(p.body()), p.htmlUrl(),
                p.asset() == null ? 0 : p.asset().size());
    }

    private ReleasePicker.Result fetchPick() throws IOException, InterruptedException {
        Flavor flavor;
        synchronized (this) {
            flavor = self.flavor();
        }
        String json = http().releasesJson();
        return ReleasePicker.check(json, running, minecraftVersion, flavor);
    }

    private synchronized void checkFailed(String why, boolean rateLimited) {
        if (state != UpdateState.CHECKING) {
            return;
        }
        state = UpdateState.CHECK_FAILED;
        error = why;
        limited = rateLimited;
        publish();
    }

    private void runDownload() {
        ReleasePicker.Pick p;
        SelfJar.Located me;
        synchronized (this) {
            p = pick;
            me = self;
        }
        ReleasePicker.Asset a = p.asset();
        Path jar = me.jar();
        Path mods = jar.getParent();
        Path part = mods.resolve(a.name() + Reconcile.PART);
        Path staged = mods.resolve(a.name() + Reconcile.NEW);
        Path fin = mods.resolve(a.name());
        String why;
        try {
            why = http().download(a, part, this::progress);
            if (why == null) {
                why = JarCheck.verify(part, me.flavor().loader(), p.version().raw(), minecraftVersion, running.channel());
            }
            if (why == null) {
                moveInto(part, staged);
            }
        } catch (UpdateHttp.Failed e) {
            why = e.getMessage();
        } catch (IOException e) {
            why = "the download didn't finish";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            why = "the download didn't finish";
        }
        if (why != null) {
            Reconcile.deleteQuietly(part);
            downloadFailed(why);
            return;
        }
        Reconcile.writeTries(Reconcile.triesFor(staged), 1);
        String spawnError = spawnHelper(jar, staged, fin);
        synchronized (this) {
            stagedName = staged.getFileName().toString();
            finalName = fin.getFileName().toString();
            state = UpdateState.READY;
            error = spawnError;
            publish();
        }
    }

    private static void moveInto(Path part, Path staged) throws IOException {
        try {
            Files.move(part, staged, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // same folder so this basically never happens, but some network drives are like that
            Files.move(part, staged, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private synchronized void progress(long n) {
        downloaded = n;
        publish();
    }

    private synchronized void downloadFailed(String why) {
        state = UpdateState.FAILED;
        error = why;
        publish();
    }

    // null when the helper is up. if it isn't, the .new stays put and the next launch's reconcile respawns it
    private static String spawnHelper(Path jar, Path staged, Path fin) {
        try {
            SwapHelper.spawn(ProcessHandle.current().pid(), jar, staged, fin);
            return null;
        } catch (IOException | RuntimeException e) {
            return "couldn't start the swap helper, it'll try again next launch";
        }
    }

    private boolean installable() {
        return self.jar() != null && self.flavor() != null && pretend == null && running != null
                && pick != null && pick.asset() != null;
    }

    private String cannotInstallReason() {
        if (pretend != null) {
            return "pretending to be " + pretend + ", install is off";
        }
        if (self.reason() != null) {
            return self.reason();
        }
        if (running == null) {
            return "this build has no release version";
        }
        return pick == null ? null : pick.notInstallable();
    }

    // callers hold the lock. canInstall is about the picked release, so the ui should only read it in AVAILABLE and
    // FAILED. a READY from reconcile has no pick and says false there, which is fine since there's nothing to click
    private void publish() {
        Path jar = self.jar();
        boolean can = installable();
        snapshot = new Snapshot(state, running == null ? realVersion : running.raw(), can, can ? null : cannotInstallReason(),
                release, downloaded, total, error, unfinished, jar == null ? null : jar.getParent(),
                jar == null ? null : jar.getFileName().toString(), stagedName, finalName, Snapshot.RELEASES_PAGE,
                switchRelease, switching);
    }

    private synchronized UpdateHttp http() {
        if (http == null) {
            http = new UpdateHttp(realVersion);
        }
        return http;
    }

    private static boolean checkForUpdates() {
        try {
            return BaritoneAPI.getSettings().checkForUpdates.value;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static String readPretend() {
        String s = System.getProperty(PRETEND);
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static java.net.URL codeSource() {
        try {
            return SwapHelper.class.getProtectionDomain().getCodeSource().getLocation();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Path gameDir() {
        try {
            return Minecraft.getInstance().gameDirectory.toPath();
        } catch (RuntimeException e) {
            return Path.of("").toAbsolutePath();
        }
    }
}
