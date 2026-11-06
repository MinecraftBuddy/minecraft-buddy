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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;
import java.util.regex.Pattern;

// picks our next release out of GET /repos/AverWasTaken/soprano/releases. every mc line shares one repo and the
// version numbers overlap (1.2.0 is the 1.21.11 line, which is OLDER mc than 1.1.6 on 26.3), so the newest number is
// not the answer. the mc version in the title is, see release.yml for where that "(26.3)" comes from
public final class ReleasePicker {

    public static final String TAG_PREFIX = "soprano-v";

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public record Asset(String name, String url, long size, String sha256) {
    }

    // asset is null when the release has nothing we can install, notInstallable says why and the ui links the page
    public record Pick(ModVersion version, String tag, String title, String htmlUrl, String body, Asset asset, String notInstallable) {
    }

    // pick is the normal update on our own track, switchTarget the newest installable build on the other one (stable
    // <-> nightly). either can be null
    public record Result(Pick pick, Pick switchTarget) {
    }

    private ReleasePicker() {
    }

    // the update only, for callers that don't care about switching tracks
    public static Pick pick(String json, ModVersion running, String minecraftVersion, Flavor flavor) {
        return check(json, running, minecraftVersion, flavor).pick();
    }

    // throws on json that isn't a release list, which the caller files under "check failed" along with every other
    // weird response
    public static Result check(String json, ModVersion running, String minecraftVersion, Flavor flavor) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) {
            throw new IllegalStateException("not a release list");
        }
        Pattern title = titlePattern(minecraftVersion);
        JsonObject best = null;
        ModVersion bestVersion = null;
        JsonObject other = null;
        ModVersion otherVersion = null;
        for (JsonElement e : (JsonArray) root) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject r = e.getAsJsonObject();
            ModVersion v = listed(r, running, title);
            if (v == null) {
                continue;
            }
            if (v.sameTrack(running)) {
                if (v.newerThan(running) && (bestVersion == null || v.newerThan(bestVersion))) {
                    best = r;
                    bestVersion = v;
                }
            } else if (switchable(v, running, minecraftVersion) && (otherVersion == null || v.newerThan(otherVersion))) {
                other = r;
                otherVersion = v;
            }
        }
        Pick update = best == null ? null : toPick(best, bestVersion, flavor);
        Pick target = other == null ? null : toPick(other, otherVersion, flavor);
        // only the newest of the other track counts. if that one can't be installed we don't go digging for an older
        // one that can, a switch button that lands you two builds behind is a weird surprise
        return new Result(update, target != null && target.asset() != null ? target : null);
    }

    // the first stable on each line that wrote its own version into build-id.properties. anything older (v1.1.6 on
    // 26.3, every 1.0.x and 1.2.x so far) gets turned down by JarCheck after the whole download, and has no updater to
    // come back with anyway. a line that isn't here has no stable worth switching to. nightlies only come from 26.3
    // and version-agnostic, which is the 26.3 line too
    private static final Map<String, ModVersion> OLDEST_SWITCHABLE_STABLE = Map.of("26.3", ModVersion.parse("1.1.7"));

    // going to nightly has to be a step forward: once 1.1.7 is out, 1.1.7-nightly.3 is the older buggier one and
    // nobody should be offered that. going back to stable is the escape hatch, so any stable will do, older included,
    // as long as it's one that can actually be installed from here
    static boolean switchable(ModVersion target, ModVersion running, String minecraftVersion) {
        if (target.isNightly()) {
            return target.newerThan(running);
        }
        ModVersion oldest = OLDEST_SWITCHABLE_STABLE.get(minecraftVersion);
        return oldest != null && !oldest.newerThan(target);
    }

    // "(26.3)" for the normal ones, "(base 26.3)" for anyversion. quoted so the dot in 26.3 doesn't also match 26x3
    static Pattern titlePattern(String minecraftVersion) {
        return Pattern.compile("\\((?:base )?" + Pattern.quote(minecraftVersion) + "\\)");
    }

    // the version of a release on our line and channel, either track, or null. newer or not is the caller's question
    static ModVersion listed(JsonObject r, ModVersion running, Pattern title) {
        // prerelease is not checked on purpose: the anyversion and nightly builds are prereleases, the channel and the
        // track already keep them away from the players they aren't for
        if (bool(r, "draft")) {
            return null;
        }
        String tag = str(r, "tag_name");
        if (tag == null || !tag.startsWith(TAG_PREFIX)) {
            return null;
        }
        ModVersion v = ModVersion.parse(tag.substring(TAG_PREFIX.length()));
        if (v == null || !v.sameChannel(running)) {
            return null;
        }
        String name = str(r, "name");
        return name != null && title.matcher(name).find() ? v : null;
    }

    static Pick toPick(JsonObject r, ModVersion v, Flavor flavor) {
        String tag = str(r, "tag_name");
        String title = str(r, "name");
        String html = str(r, "html_url");
        String body = str(r, "body");
        if (flavor == null) {
            return new Pick(v, tag, title, html, body, null, "couldn't tell which jar you're running");
        }
        String want = flavor.assetName(v.raw());
        JsonObject a = findAsset(r, want);
        if (a == null) {
            return new Pick(v, tag, title, html, body, null, "this release has no " + want);
        }
        String sha = sha256(str(a, "digest"));
        String url = str(a, "browser_download_url");
        long size = a.has("size") && a.get("size").isJsonPrimitive() ? a.get("size").getAsLong() : -1;
        if (sha == null) {
            // no checksum, no install. a jar we can't verify is a jar we don't swap in behind someone's back
            return new Pick(v, tag, title, html, body, null, "github didn't give a checksum for " + want);
        }
        if (size <= 0 || !HostPin.allowed(url)) {
            return new Pick(v, tag, title, html, body, null, "the download link for " + want + " looks wrong");
        }
        return new Pick(v, tag, title, html, body, new Asset(want, url, size, sha), null);
    }

    static JsonObject findAsset(JsonObject r, String name) {
        JsonElement assets = r.get("assets");
        if (assets == null || !assets.isJsonArray()) {
            return null;
        }
        for (JsonElement e : assets.getAsJsonArray()) {
            if (e.isJsonObject() && name.equals(str(e.getAsJsonObject(), "name"))) {
                return e.getAsJsonObject();
            }
        }
        return null;
    }

    // "sha256:<hex>" -> "<hex>", anything else (null, md5, a truncated hash) -> null
    static String sha256(String digest) {
        if (digest == null || !digest.startsWith("sha256:")) {
            return null;
        }
        String hex = digest.substring("sha256:".length()).toLowerCase(java.util.Locale.ROOT);
        return SHA256.matcher(hex).matches() ? hex : null;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }
}
