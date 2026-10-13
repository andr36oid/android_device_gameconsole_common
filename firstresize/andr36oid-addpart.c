/*
 * andr36oid-addpart <disk> <number> <first sector> <last sector>
 *
 * Tells the running kernel about a partition that was just added to the
 * partition table (512-byte sectors), so it can be used without a restart.
 * Re-reading the whole table (BLKRRPART, partprobe) doesn't work while the
 * system partition on the same card is mounted; adding one partition does.
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
	long long first, last;
	int fd;

	if (argc != 5) {
		fprintf(stderr, "usage: %s <disk> <number> <first sector> <last sector>\n", argv[0]);
		return 2;
	}
	first = atoll(argv[3]);
	last = atoll(argv[4]);
	memset(&part, 0, sizeof(part));
	part.pno = atoi(argv[2]);
	part.start = first * 512;
	part.length = (last - first + 1) * 512;
	if (part.pno <= 0 || first <= 0 || last < first) {
		fprintf(stderr, "%s: bad partition %s %s-%s\n", argv[0], argv[2], argv[3], argv[4]);
		return 2;
	}
	memset(&arg, 0, sizeof(arg));
	arg.op = BLKPG_ADD_PARTITION;
	arg.datalen = sizeof(part);
	arg.data = &part;

	fd = open(argv[1], O_RDONLY | O_CLOEXEC);
	if (fd < 0) {
		fprintf(stderr, "%s: %s: %s\n", argv[0], argv[1], strerror(errno));
		return 1;
	}
	if (ioctl(fd, BLKPG, &arg) < 0) {
		fprintf(stderr, "%s: adding partition %d to %s: %s\n", argv[0], part.pno, argv[1],
			strerror(errno));
		close(fd);
		return 1;
	}
	close(fd);
	return 0;
}
