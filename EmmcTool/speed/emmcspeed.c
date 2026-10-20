/*
 * andr36oid-emmcspeed DEVICE SECONDS
 *
 * The read speed test of Settings > System > Built-in eMMC. Only reads, the device is
 * opened read-only: first 1 MB blocks one after the other from the start, then 4 KB
 * blocks at random places on the whole device, for SECONDS each. The first is what
 * copying a big file looks like, the second what loading many small files does.
 *
 * O_DIRECT keeps the page cache out of it. If the device refuses O_DIRECT, read-ahead
 * is turned off and the cache dropped instead.
 *
 * Prints key=value lines for andr36oid-emmc.sh:
 *   seq_bytes=N seq_s=S rr_ops=N rr_s=S direct=0|1     or     speed_error=open|size|read
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <unistd.h>

#define BLOCK 4096
#define BIG (1024 * 1024)
#define MIN_BYTES (16 * BIG)

static uint64_t rng_state;

static uint64_t rng(void)
{
	/* xorshift64*, plenty for picking places on a chip */
	rng_state ^= rng_state >> 12;
	rng_state ^= rng_state << 25;
	rng_state ^= rng_state >> 27;
	return rng_state * 2685821657736338717ULL;
}

static double now(void)
{
	struct timespec ts;

	clock_gettime(CLOCK_MONOTONIC, &ts);
	return ts.tv_sec + ts.tv_nsec / 1e9;
}

static int fail(const char *what)
{
	printf("speed_error=%s\n", what);
	return 2;
}

int main(int argc, char **argv)
{
	unsigned char *buf;
	double secs, t0, t, seq_s, rr_s;
	unsigned long long seq_bytes = 0;
	unsigned long rr_ops = 0;
	off_t size, off;
	uint64_t blocks;
	int fd, direct = 1;

	if (argc != 3) {
		fprintf(stderr, "usage: %s DEVICE SECONDS\n", argv[0]);
		return 1;
	}
	secs = atof(argv[2]);
	if (!(secs > 0) || secs > 600) {
		fprintf(stderr, "SECONDS must be 1..600\n");
		return 1;
	}
	rng_state = (uint64_t)time(NULL) ^ ((uint64_t)getpid() << 32) ^ 0x9e3779b97f4a7c15ULL;

	fd = open(argv[1], O_RDONLY | O_DIRECT | O_CLOEXEC);
	if (fd < 0 && errno == EINVAL) {
		direct = 0;
		fd = open(argv[1], O_RDONLY | O_CLOEXEC);
	}
	if (fd < 0)
		return fail("open");
	/* a block device's size; fstat says 0 for those */
	size = lseek(fd, 0, SEEK_END);
	if (size < MIN_BYTES)
		return fail("size");
	blocks = (uint64_t)size / BLOCK;
	if (!direct) {
		posix_fadvise(fd, 0, 0, POSIX_FADV_DONTNEED);
		posix_fadvise(fd, 0, 0, POSIX_FADV_RANDOM);
	}
	if (posix_memalign((void **)&buf, BLOCK, BIG) != 0)
		return fail("memory");

	/* 1 MB blocks from the start, wrapping round on a small device */
	t0 = now();
	t = t0;
	off = 0;
	while (t - t0 < secs) {
		if (off + BIG > size)
			off = 0;
		if (pread(fd, buf, BIG, off) != BIG)
			return fail("read");
		seq_bytes += BIG;
		off += BIG;
		t = now();
	}
	seq_s = t - t0;

	/* 4 KB blocks at random places */
	t0 = now();
	t = t0;
	while (t - t0 < secs) {
		if (pread(fd, buf, BLOCK, (off_t)(rng() % blocks) * BLOCK) != BLOCK)
			return fail("read");
		rr_ops++;
		t = now();
	}
	rr_s = t - t0;
	close(fd);

	printf("seq_bytes=%llu\nseq_s=%.3f\nrr_ops=%lu\nrr_s=%.3f\ndirect=%d\n",
			seq_bytes, seq_s, rr_ops, rr_s, direct);
	return 0;
}
