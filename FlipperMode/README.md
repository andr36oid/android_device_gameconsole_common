# Flipper mode core

Milestone 1 is an original Kotlin/JVM interpreter and STM32WB55 boot scaffold.
Official f7 firmware 1.5.1 reaches its first processed FreeRTOS scheduler tick.
There is no Android screen or USB bridge yet.

Run the host tests from the overlay root:

```sh
export FLIPPER_SCRATCH=/path/to/authorized/scratch
FlipperMode/run.sh
```

This selects `kotlinc` on PATH or `/andr36oid/src/external/kotlinc/bin/kotlinc`,
compiles for JVM 8, runs the instruction/peripheral tests, and runs the firmware
regression. Set `KOTLINC` for another compiler. Java and Kotlin's standard library
are the only runtime dependencies; there is no Android, Gradle, coroutines or
native emulator dependency. Set `FLIPPER_SCRATCH` to an authorized directory;
the runner requires it explicitly and removes temporary builds on exit.

The real boot test uses a pinned, checksum-verified flat flash image at
`/andr36oid/work/reports/flipper/fw/flipper-z-f7-full-1.5.1.bin`. Download it
separately when needed:

```sh
FlipperMode/fetch-firmware.sh
FlipperMode/run.sh /path/outside/the/repo/flipper-z-f7-full-1.5.1.bin
```

The script downloads the official GPL-3.0 firmware, which must stay outside this
repository. If the default image is absent, the script clearly skips the real
boot test and still runs the hand-written Thumb boot program. An explicitly
requested missing image, wrong checksum, CPU stop or missed boot checkpoint
fails the regression. The checked boot initializes poisoned RAM, compares the
data/BSS/mailbox contents, checks the PC path and PLL clock, and proves that the
first SysTick calls `xTaskIncrementTick`, increments `xTickCount` to one, and
returns to thread mode.

For diagnostic execution beyond that boundary, or another raw flash image:

```sh
FlipperMode/run.sh --probe /path/to/full.bin 1000000
```

Probe mode prints stops without treating them as a passing boot test. The budget
counts steps, including sleeping cycles, so WFI cannot make the harness hang.
DFU/ELF containers and application-only images require extraction/rebasing before
loading; the harness expects a flat image based at `0x08000000`.

`src/` is the library, `tests/` contains host-only tests, and `host/` contains the
file-loading and trace harness. A standalone core jar can be built with normal
Kotlin tools:

```sh
kotlinc FlipperMode/src/org/andr36oid/flipper/core/*.kt -jvm-target 1.8 -d /path/outside/the/repo/flipper-core.jar
```

`Android.bp` declares the future Soong static library `andr36oid-flipper-core`.
It has not been built with AOSP. Only the library sources are in its source set.

The implemented subset includes common scalar Thumb/Thumb-2 integer operations,
IT, branches/calls, memory/stack transfers, exclusive words, special registers,
basic exceptions, NVIC, SysTick, DWT and MPU permissions. Peripherals implement
only the documented boot subset. I2C1 has no attached slaves and NACKs; UART TX
and oscillator readiness complete immediately. Time is one virtual cycle per
step, with no Cortex-M4 pipeline or bus timing simulation.

Unsupported instructions and registers throw `EmulationStop` with the guest PC,
encoding and address. These stops are terminal diagnostic boundaries; partial
memory effects are not rolled back. FP instructions/extended frames, most DSP,
full privilege/system fault escalation, CPU2, bit-band, peripheral transfer
engines, display, storage and USB remain unfinished. CPU reset clears CPU/SCS
state; constructing a new `Stm32wb55` gives fresh board/peripheral state.

Register comments cite ARM DDI 0403D and ST RM0434 **English Rev 4** sections.
Current RM0434 editions have different chapter numbering. Code was written from
the manuals and official firmware; no existing emulator implementation is used.
Detailed source evidence, memory map, peripheral inventory, performance risks,
trace logs and milestone status are in `/andr36oid/work/reports/flipper/`.
