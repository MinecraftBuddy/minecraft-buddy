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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

// the two github calls, kept away from Updater so they run without minecraft on the classpath. every url is pinned
// before we ask and again after the redirects, a json field pointing somewhere odd doesn't get a request at all
final class UpdateHttp {

    // three mc lines times two channels share this list, 100 keeps ours on page one for a long while
    static final String API = "https://api.github.com/repos/AverWasTaken/soprano/releases?per_page=100";
    static final long STALL_MS = 30_000;

    private final HttpClient http;
    private final String userAgent;

    UpdateHttp(String version) {
        // NORMAL never goes https -> http, and the final uri gets pinned again after the redirects anyway
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.userAgent = "soprano-updater/" + version;
    }

    // the release list as json, or Failed with words a player can read
    String releasesJson() throws IOException, InterruptedException {
        HttpRequest req = request(API, "application/vnd.github+json").timeout(Duration.ofSeconds(15)).GET().build();
        // the request timeout stops at the headers, a body that trickles in forever would park the updater thread,
        // so the whole thing gets a hard cap on top
        CompletableFuture<HttpResponse<String>> f = http.sendAsync(req, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> resp;
        try {
            resp = f.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new IOException("timed out");
        } catch (InterruptedException e) {
            f.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        }
        if (!HostPin.allowed(resp.uri())) {
            throw new Failed("github sent us somewhere odd");
        }
        int code = resp.statusCode();
        if (rateLimited(code, resp.headers().firstValue("x-ratelimit-remaining").orElse(null))) {
            throw new Failed(RATE_LIMIT, true);
        }
        if (code != 200) {
            throw new Failed("github said " + code);
        }
        return resp.body();
    }

    static final String RATE_LIMIT = "hit github's rate limit, try again in a bit";

    // a 403 alone isn't the rate limit (blocked ip, abuse flag, a proxy), github says so with 429 or an empty bucket
    static boolean rateLimited(int code, String remaining) {
        return code == 429 || (code == 403 && remaining != null && remaining.strip().equals("0"));
    }

    // null when part holds exactly the bytes github promised, a reason when it doesn't
    String download(ReleasePicker.Asset a, Path part, LongConsumer progress) throws IOException, InterruptedException {
        // octet-stream, not the api's vnd type: github.com can answer a json accept with json instead of the file
        HttpRequest req = request(a.url(), "application/octet-stream").timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = resp.body()) {
            if (!HostPin.allowed(resp.uri())) {
                return "the download got sent somewhere odd";
            }
            if (resp.statusCode() != 200) {
                return "github said " + resp.statusCode();
            }
            MessageDigest md = JarCheck.newSha256();
            long n = copy(in, part, md, a.size(), STALL_MS, progress);
            if (n != a.size()) {
                return "the download was the wrong size";
            }
            if (!HexFormat.of().formatHex(md.digest()).equals(a.sha256())) {
                return "the download's checksum didn't match";
            }
            return null;
        }
    }

    private HttpRequest.Builder request(String url, String accept) {
        if (!HostPin.allowed(url)) {
            throw new Failed("refusing to download from there");
        }
        return HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgent)
                .header("Accept", accept)
                .header("X-GitHub-Api-Version", "2022-11-28");
    }

    // the request timeout only covers the headers, a connection that goes quiet mid body would hang the read
    // forever. the watchdog closes the stream when nothing arrived for stallMs, which unblocks it (an exception or an
    // early end, either way the size check fails it).
    // returns the byte count, which is past expected (and the rest unread) when the server sends too much
    static long copy(InputStream in, Path part, MessageDigest md, long expected, long stallMs, LongConsumer progress) throws IOException {
        AtomicLong last = new AtomicLong(System.currentTimeMillis());
        AtomicBoolean done = new AtomicBoolean();
        Thread watchdog = new Thread(() -> {
            while (!done.get()) {
                try {
                    Thread.sleep(Math.min(1000, stallMs));
                } catch (InterruptedException e) {
                    return;
                }
                if (!done.get() && System.currentTimeMillis() - last.get() > stallMs) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                        // closing is the whole point, if it complains the read throws anyway
                    }
                    return;
                }
            }
        }, "soprano-updater-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        long n = 0;
        long lastPublish = 0;
        try (OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) >= 0) {
                n += r;
                // more than github promised is already wrong, don't fill someone's disk to find out by how much
                if (n > expected) {
                    return n;
                }
                out.write(buf, 0, r);
                md.update(buf, 0, r);
                long now = System.currentTimeMillis();
                last.set(now);
                if (now - lastPublish > 100) {
                    lastPublish = now;
                    progress.accept(n);
                }
            }
        } finally {
            done.set(true);
            watchdog.interrupt();
        }
        progress.accept(n);
        return n;
    }

    // a failure we can put in words, no stack trace needed
    static final class Failed extends RuntimeException {

        // the rate limit one doesn't get an automatic second try, that would just be asking again for the same no
        final boolean rateLimit;

        Failed(String message) {
            this(message, false);
        }

        Failed(String message, boolean rateLimit) {
            super(message, null, false, false);
            this.rateLimit = rateLimit;
        }
    }
}
