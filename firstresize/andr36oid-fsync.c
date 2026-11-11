/*
 * andr36oid-fsync <file>...
 *
 * Opens each file read-only and fsync()s it, so a line just appended to
 * BOOT/resize-log.txt survives a cold power-off. toybox/toolbox sync is
 * global-only (sync(2)); the resizer needs a per-FILE flush of the small vfat
 * log without also flushing the large AUTOAPK copy and resize I/O. On vfat
 * fsync flushes the file data and its directory entry (the new size), which is
 * what makes the last marker readable after an interrupted boot.
 */
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv)
{
	int i, fd, rc = 0;

	for (i = 1; i < argc; i++) {
		fd = open(argv[i], O_RDONLY);
		if (fd < 0) {
			fprintf(stderr, "andr36oid-fsync: open %s: %s\n",
				argv[i], strerror(errno));
			rc = 1;
			continue;
		}
		if (fsync(fd) < 0) {
			fprintf(stderr, "andr36oid-fsync: fsync %s: %s\n",
				argv[i], strerror(errno));
			rc = 1;
		}
		close(fd);
	}
	return rc;
}
