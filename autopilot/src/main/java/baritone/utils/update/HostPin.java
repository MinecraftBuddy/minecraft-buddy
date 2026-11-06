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

import java.net.URI;
import java.util.Locale;

// the only places a jar we're about to run on someone's machine may come from. checked on the url we ask for and
// again on where the redirects actually dropped us, a json field pointing at evil.example is still evil
public final class HostPin {

    private HostPin() {
    }

    public static boolean allowed(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null) {
            return false;
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        // the leading dot matters, evilgithubusercontent.com is not one of us
        return host.equals("api.github.com") || host.equals("github.com") || host.endsWith(".githubusercontent.com");
    }

    public static boolean allowed(String url) {
        if (url == null) {
            return false;
        }
        try {
            return allowed(new URI(url));
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }
}
