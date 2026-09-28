package org.andr36oid.wifitransfer;

import java.io.File;
import java.util.List;

/** A top folder the web page may see: EASYROMS, internal storage, a USB drive. */
final class Root {

    /** Where the roots come from; asked again for every request, cards come and go. */
    interface Source {
        List<Root> roots();
    }

    /** Short name used in URLs, e.g. "easyroms". */
    final String id;
    /** Shown on the page, e.g. "EASYROMS". */
    final String name;
    final File dir;

    Root(String id, String name, File dir) {
        this.id = id;
        this.name = name;
        this.dir = dir;
    }
}
