/*
 * andr36oid-addpart <disk> <number> <first sector> <last sector>
 * andr36oid-addpart -r <disk> <number> <first sector> <last sector>
 * andr36oid-addpart -d <disk> <number>
 *
 * Tells the running kernel about a partition that was just added to the
 * partition table (512-byte sectors), so it can be used without a restart; -r:
 * one that got bigger or smaller (same first sector), -d: one that was removed.
 * Re-reading the whole table (BLKRRPART, partprobe) doesn't work while the
 * system partition on the same card is mounted; changing one partition does.
 * The kernel gets no name this way, so by-name links have to be made by hand.
 */
#include <errno.h>
#include <fcntl.h>
#include <linux/blkpg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

int main(int argc, char **argv)
{
	struct blkpg_partition part;
	struct blkpg_ioctl_arg arg;
	long long first = 0, last = 0;
	const char *prog = argv[0], *what = "adding";
	int op = BLKPG_ADD_PARTITION;
	int fd;

	if (argc > 1 && strcmp(argv[1], "-d") == 0) {
		op = BLKPG_DEL_PARTITION;
		what = "removing";
	} else if (argc > 1 && strcmp(argv[1], "-r") == 0) {
		op = BLKPG_RESIZE_PARTITION;
		what = "resizing";
	}
	if (op != BLKPG_ADD_PARTITION) {
		argv++;
		argc--;
	}
	if (argc != (op == BLKPG_DEL_PARTITION ? 3 : 5)) {
		fprintf(stderr, "usage: %s [-r] <disk> <number> <first sector> <last sector>\n"
			"       %s -d <disk> <number>\n", prog, prog);
		return 2;
	}
	memset(&part, 0, sizeof(part));
	part.pno = atoi(argv[2]);
	if (op != BLKPG_DEL_PARTITION) {
		first = atoll(argv[3]);
		last = atoll(argv[4]);
		part.start = first * 512;
		part.length = (last - first + 1) * 512;
	}
	if (part.pno <= 0 || (op != BLKPG_DEL_PARTITION && (first <= 0 || last < first))) {
		fprintf(stderr, "%s: bad partition %s\n", prog, argv[2]);
		return 2;
	}
	memset(&arg, 0, sizeof(arg));
	arg.op = op;
	arg.datalen = sizeof(part);
	arg.data = &part;

	fd = open(argv[1], O_RDONLY | O_CLOEXEC);
	if (fd < 0) {
		fprintf(stderr, "%s: %s: %s\n", prog, argv[1], strerror(errno));
		return 1;
	}
	if (ioctl(fd, BLKPG, &arg) < 0) {
		fprintf(stderr, "%s: %s partition %d of %s: %s\n", prog, what, part.pno, argv[1],
			strerror(errno));
		close(fd);
		return 1;
	}
	close(fd);
	return 0;
}
