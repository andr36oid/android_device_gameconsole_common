package org.andr36oid.savebackup;

import java.io.File;

/** One save file found on the console. */
final class SaveFile {
    /** "internal/…" or "easyroms/…", see Roots. */
    final String id;
    final File file;
    final long size;
    final long mtime;
    /** Which game: "game:zelda (usa)" or "psp:ULUS10041". */
    String key;
    /** The game as shown: "Zelda (USA)", or a PSP game's title. */
    String label;
    /** The folder it's in, "gba", "saves", "PSP". */
    String folder;

    SaveFile(String id, File file, long size, long mtime) {
        this.id = id;
        this.file = file;
        this.size = size;
        this.mtime = mtime;
    }
}
