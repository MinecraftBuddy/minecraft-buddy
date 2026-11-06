package baritone.utils.update;

import org.junit.Test;

import java.net.URI;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HostPinAndMarkdownTest {

    @Test
    public void onlyGithubOverHttps() {
        assertTrue(HostPin.allowed(URI.create("https://api.github.com/repos/AverWasTaken/soprano/releases")));
        assertTrue(HostPin.allowed(URI.create("https://github.com/AverWasTaken/soprano/releases/download/x/y.jar")));
        assertTrue(HostPin.allowed(URI.create("https://objects.githubusercontent.com/github-production-release-asset/1")));
        assertTrue(HostPin.allowed(URI.create("https://release-assets.githubusercontent.com/x")));
        assertTrue(HostPin.allowed(URI.create("https://GitHub.com/x")));
        assertTrue(HostPin.allowed(URI.create("https://github.com:443/x")));

        assertFalse(HostPin.allowed(URI.create("http://github.com/x")));
        assertFalse(HostPin.allowed(URI.create("https://evilgithubusercontent.com/x")));
        assertFalse(HostPin.allowed(URI.create("https://github.com.evil.example/x")));
        assertFalse(HostPin.allowed(URI.create("https://evil.example/github.com")));
        assertFalse(HostPin.allowed(URI.create("https://githubusercontent.com/x")));
        assertFalse(HostPin.allowed(URI.create("https://user@github.com/x")));
        assertFalse(HostPin.allowed(URI.create("https://github.com:8443/x")));
        assertFalse(HostPin.allowed(URI.create("file:///etc/passwd")));
        assertFalse(HostPin.allowed((URI) null));
        assertFalse(HostPin.allowed((String) null));
        assertFalse(HostPin.allowed("not a url at all"));
    }

    @Test
    public void markdownBecomesPlainText() {
        String md = "### What's new\r\n\r\n**New stuff**\r\n\r\n- multi-ore `#get` works\r\n"
                + "- see [the docs](https://github.com/AverWasTaken/soprano/blob/26.3/USAGE.md) for more\r\n"
                + "![shot](https://x/y.png)\r\n<!-- hidden -->\r\n\r\n\r\n\r\n# Big\n  ## indented heading";
        String plain = Markdown.plain(md);
        assertEquals("What's new\n\nNew stuff\n\n- multi-ore #get works\n- see the docs for more\nshot\n\nBig\nindented heading", plain);
        assertEquals("", Markdown.plain(null));
        // a # mid line is a hashtag or a command, not a heading
        assertEquals("run #mine diamond_ore", Markdown.plain("run #mine diamond_ore"));
    }
}
