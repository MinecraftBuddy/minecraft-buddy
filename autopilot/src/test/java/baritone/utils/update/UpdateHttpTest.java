package baritone.utils.update;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class UpdateHttpTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void onlyTheRealRateLimitSaysSo() {
        assertTrue(UpdateHttp.rateLimited(429, null));
        assertTrue(UpdateHttp.rateLimited(403, "0"));
        assertTrue(!UpdateHttp.rateLimited(403, "42"));
        assertTrue(!UpdateHttp.rateLimited(403, null));
        assertTrue(!UpdateHttp.rateLimited(500, "0"));
    }

    @Test
    public void autoRetryIsOnceAndNotForTheRateLimit() {
        assertTrue(UpdateState.shouldAutoRetry(UpdateState.CHECK_FAILED, false, false));
        assertTrue(!UpdateState.shouldAutoRetry(UpdateState.CHECK_FAILED, true, false));
        assertTrue(!UpdateState.shouldAutoRetry(UpdateState.CHECK_FAILED, false, true));
        assertTrue(!UpdateState.shouldAutoRetry(UpdateState.UP_TO_DATE, false, false));
    }

    @Test
    public void copiesAndHashes() throws Exception {
        Path part = tmp.getRoot().toPath().resolve("x.part");
        java.security.MessageDigest md = JarCheck.newSha256();
        AtomicLong seen = new AtomicLong();
        long n = UpdateHttp.copy(new ByteArrayInputStream("abc".getBytes()), part, md, 3, 5000, seen::set);
        assertEquals(3, n);
        assertEquals(3, seen.get());
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", HexFormat.of().formatHex(md.digest()));
        assertEquals("abc", Files.readString(part));
    }

    @Test
    public void tooManyBytesStopsEarly() throws Exception {
        Path part = tmp.getRoot().toPath().resolve("x.part");
        long n = UpdateHttp.copy(new ByteArrayInputStream(new byte[10]), part, JarCheck.newSha256(), 4, 5000, v -> { });
        assertTrue(n > 4);
    }

    @Test(timeout = 10_000)
    public void aStalledStreamGetsClosed() throws Exception {
        // reads block until somebody closes the stream, like a server that went quiet mid body
        CountDownLatch closed = new CountDownLatch(1);
        InputStream stuck = new InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    closed.await();
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
                throw new IOException("closed");
            }

            @Override
            public void close() {
                closed.countDown();
            }
        };
        try {
            UpdateHttp.copy(stuck, tmp.getRoot().toPath().resolve("x.part"), JarCheck.newSha256(), 100, 300, v -> { });
            fail("a stalled read should end in an exception");
        } catch (IOException expected) {
            assertEquals(0, closed.getCount());
        }
    }
}
