/*
 * andr36oid-cardspeed FILE SECONDS
 *
 * The small-block half of Settings > Storage > SD card check. Reads, then writes, 4 KB
 * blocks at random places inside FILE (the 256 MB file the speed test just wrote), for
 * SECONDS each, one at a time. This is how the A1/A2 marks on SD cards are measured, and
 * what loading a game made of many small files looks like to the card.
 *
 * O_DIRECT keeps the page cache out of it. If the file system refuses O_DIRECT, the file
 * is opened with O_DSYNC, read-ahead is turned off, and every block is used at most once
 * per pass, so nothing is read from the cache.
 *
 * Prints key=value lines for andr36oid-cardcheck.sh:
 *   rr_ops=N rr_s=S rw_ops=N rw_s=S direct=0|1     or     rand_error=open|size|read|write
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define BLOCK 4096
#define MIN_BLOCKS 256

static uint64_t rng_state;

static uint64_t rng(void)
{
	/* xorshift64*, plenty for picking places on a card */
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

/* a random order of all blocks, so no block comes twice in one pass */
static void shuffle(uint32_t *order, uint32_t n)
{
	uint32_t i;

	for (i = 0; i < n; i++)
		order[i] = i;
	for (i = n - 1; i > 0; i--) {
		uint32_t j = (uint32_t)(rng() % (i + 1));
		uint32_t t = order[i];

		order[i] = order[j];
		order[j] = t;
	}
}

static int fail(const char *what)
{
	printf("rand_error=%s\n", what);
	return 2;
}

int main(int argc, char **argv)
{
	struct stat st;
	uint32_t *order, blocks, i;
	unsigned char *buf;
	double secs, t0, t, rr_s, rw_s;
	unsigned long rr_ops = 0, rw_ops = 0;
	int fd, direct = 1;

	if (argc != 3) {
		fprintf(stderr, "usage: %s FILE SECONDS\n", argv[0]);
		return 1;
	}
	secs = atof(argv[2]);
	if (!(secs > 0) || secs > 600) {
		fprintf(stderr, "SECONDS must be 1..600\n");
		return 1;
	}
	rng_state = (uint64_t)time(NULL) ^ ((uint64_t)getpid() << 32) ^ 0x9e3779b97f4a7c15ULL;

	fd = open(argv[1], O_RDWR | O_DIRECT | O_CLOEXEC);
	if (fd < 0 && errno == EINVAL) {
		direct = 0;
		fd = open(argv[1], O_RDWR | O_DSYNC | O_CLOEXEC);
	}
	if (fd < 0)
		return fail("open");
	if (fstat(fd, &st) != 0 || st.st_size / BLOCK < MIN_BLOCKS
			|| st.st_size / BLOCK > UINT32_MAX)
		return fail("size");
	blocks = (uint32_t)(st.st_size / BLOCK);
	if (!direct) {
		posix_fadvise(fd, 0, 0, POSIX_FADV_DONTNEED);
		posix_fadvise(fd, 0, 0, POSIX_FADV_RANDOM);
	}

	order = malloc(sizeof(*order) * blocks);
	if (order == NULL || posix_memalign((void **)&buf, BLOCK, BLOCK) != 0)
		return fail("memory");

	/* random reads */
	shuffle(order, blocks);
	t0 = now();
	t = t0;
	for (i = 0; i < blocks && t - t0 < secs; i++) {
		if (pread(fd, buf, BLOCK, (off_t)order[i] * BLOCK) != BLOCK)
			return fail("read");
		rr_ops++;
		t = now();
	}
	rr_s = t - t0;

	/* random writes; the time to flush them is part of it */
	for (i = 0; i < BLOCK; i++)
		buf[i] = (unsigned char)rng();
	shuffle(order, blocks);
	t0 = now();
	t = t0;
	for (i = 0; i < blocks && t - t0 < secs; i++) {
		memcpy(buf, &i, sizeof(i));
		if (pwrite(fd, buf, BLOCK, (off_t)order[i] * BLOCK) != BLOCK)
			return fail("write");
		rw_ops++;
		t = now();
	}
	if (fdatasync(fd) != 0)
		return fail("write");
	rw_s = now() - t0;
	close(fd);

	printf("rr_ops=%lu\nrr_s=%.3f\nrw_ops=%lu\nrw_s=%.3f\ndirect=%d\n",
			rr_ops, rr_s, rw_ops, rw_s, direct);
	return 0;
}
