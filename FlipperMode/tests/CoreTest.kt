package org.andr36oid.flipper.tests

import org.andr36oid.flipper.core.*

private var passed = 0
private var failed = 0
private fun test(name: String, body: () -> Unit) {
    try { body(); passed++; println("PASS $name") }
    catch (e: Throwable) { failed++; println("FAIL $name: ${e.message}"); e.printStackTrace() }
}
private fun eq(expected: Int, actual: Int) { check(expected == actual) { "expected ${hex(expected)}, got ${hex(actual)}" } }
private fun yes(value: Boolean) { check(value) }
private fun stops(fragment: String, body: () -> Unit) {
    try { body() } catch (e: EmulationStop) { val message = e.message ?: ""; check(message.contains(fragment)) { message }; return }
    error("expected EmulationStop containing $fragment")
}

private class Fixture(vararg words: Int) {
    val bus = MemoryBus()
    val memory = Ram(0x10000)
    val system = SystemControl()
    val cpu = CortexM4(bus, system)
    init {
        bus.map(0, 0x10000, "test RAM", memory)
        bus.map(0xe000e000.toInt(), 0x1000, "SCS", system)
        bus.write32(0, 0xf000)
        bus.write32(4, 0x101)
        words.forEachIndexed { i, word -> bus.write16(0x100 + i * 2, word) }
        cpu.reset()
    }
    fun reg(index: Int, value: Int) { cpu.writeRegister(index, value) }
    fun reg(index: Int) = cpu.readRegister(index)
    fun run(count: Int) { repeat(count) { cpu.step() } }
    fun flags(expected: Int) = eq(expected, cpu.apsr and 0xf0000000.toInt())
}

// Expected arithmetic follows ARM DDI 0403D A7.4.2 AddWithCarry/Shift_C and
// the named A7.7 instruction descriptions. Constants are hand-derived, not
// computed by the implementation being tested. Halfwords are in fetch order.
private fun arithmetic() {
    test("ADDS unsigned carry and zero") {
        val f = Fixture(0x3001); f.reg(0, -1); f.run(1); eq(0, f.reg(0)); f.flags(CortexM4.Z or CortexM4.C)
    }
    test("ADDS positive signed overflow") {
        val f = Fixture(0x3001); f.reg(0, Int.MAX_VALUE); f.run(1); eq(Int.MIN_VALUE, f.reg(0)); f.flags(CortexM4.N or CortexM4.V)
    }
    test("ADDS negative signed overflow with carry") {
        val f = Fixture(0x1840); f.reg(0, Int.MIN_VALUE); f.reg(1, Int.MIN_VALUE); f.run(1)
        eq(0, f.reg(0)); f.flags(CortexM4.Z or CortexM4.C or CortexM4.V)
    }
    test("SUBS borrow") {
        val f = Fixture(0x3801); f.run(1); eq(-1, f.reg(0)); f.flags(CortexM4.N)
    }
    test("SUBS no borrow and signed overflow") {
        val f = Fixture(0x3801); f.reg(0, Int.MIN_VALUE); f.run(1); eq(Int.MAX_VALUE, f.reg(0)); f.flags(CortexM4.C or CortexM4.V)
    }
    test("ADCS carry input") {
        val f = Fixture(0x4148); f.reg(0, -1); f.reg(1, 0); f.cpu.apsr = CortexM4.C; f.run(1)
        eq(0, f.reg(0)); f.flags(CortexM4.Z or CortexM4.C)
    }
    test("SBCS consumes inverted borrow") {
        val f = Fixture(0x4188); f.reg(0, 5); f.reg(1, 5); f.run(1); eq(-1, f.reg(0)); f.flags(CortexM4.N)
    }
    test("RSBS zero minus minimum integer") {
        val f = Fixture(0x4248); f.reg(1, Int.MIN_VALUE); f.run(1); eq(Int.MIN_VALUE, f.reg(0)); f.flags(CortexM4.N or CortexM4.V)
    }
    test("CMP preserves operands") {
        val f = Fixture(0x4288); f.reg(0, 7); f.reg(1, 7); f.run(1); eq(7, f.reg(0)); f.flags(CortexM4.Z or CortexM4.C)
    }
    test("CMN signed overflow") {
        val f = Fixture(0x42c8); f.reg(0, Int.MAX_VALUE); f.reg(1, 1); f.run(1); eq(Int.MAX_VALUE, f.reg(0)); f.flags(CortexM4.N or CortexM4.V)
    }
    test("logical AND EOR ORR BIC MVN TST preserve CV") {
        val cases = listOf(0x4008 to 0x0a, 0x4048 to 0x55, 0x4308 to 0x5f, 0x4388 to 0x05, 0x43c8 to 0xffffffa5.toInt())
        for ((op, expected) in cases) {
            val f = Fixture(op); f.reg(0, 15); f.reg(1, 0x5a); f.cpu.apsr = CortexM4.C or CortexM4.V
            f.run(1); eq(expected, f.reg(0)); yes(f.cpu.c && f.cpu.v)
        }
        val f = Fixture(0x4208); f.reg(0, 0xf0); f.reg(1, 15); f.run(1); eq(0xf0, f.reg(0)); yes(f.cpu.z)
    }
    test("MULS low word with CV unchanged") {
        val f = Fixture(0x4348); f.reg(0, 0x40000000); f.reg(1, 4); f.cpu.apsr = CortexM4.V; f.run(1)
        eq(0, f.reg(0)); f.flags(CortexM4.Z or CortexM4.V)
    }
    test("MOVS keeps carry and overflow") {
        val f = Fixture(0x20ff); f.cpu.apsr = CortexM4.N or CortexM4.C or CortexM4.V; f.run(1)
        eq(255, f.reg(0)); f.flags(CortexM4.C or CortexM4.V)
    }
}

private fun shifts() {
    // A7.7 LSL/LSR/ASR/ROR: count zero preserves C; 32 and >32 differ.
    val cases = listOf(
        intArrayOf(0x4088, 0, 0x80000001.toInt(), 1),
        intArrayOf(0x4088, 1, 2, 1), intArrayOf(0x4088, 31, Int.MIN_VALUE, 0),
        intArrayOf(0x4088, 32, 0, 1), intArrayOf(0x4088, 33, 0, 0),
        intArrayOf(0x40c8, 1, 0x40000000, 1), intArrayOf(0x40c8, 32, 0, 1),
        intArrayOf(0x40c8, 255, 0, 0), intArrayOf(0x4108, 32, -1, 1),
        intArrayOf(0x4108, 255, -1, 1), intArrayOf(0x41c8, 32, 0x80000001.toInt(), 1),
        intArrayOf(0x41c8, 33, 0xc0000000.toInt(), 1), intArrayOf(0x4088, 256, 0x80000001.toInt(), 1))
    for (row in cases) test("register shift ${row[0].toString(16)} count=${row[1]}") {
        val f = Fixture(row[0]); f.reg(0, 0x80000001.toInt()); f.reg(1, row[1]); f.cpu.apsr = CortexM4.C or CortexM4.V
        f.run(1); eq(row[2], f.reg(0)); eq(row[3], if (f.cpu.c) 1 else 0); yes(f.cpu.v)
    }
    test("LSRS immediate zero means 32") {
        val f = Fixture(0x0808); f.reg(1, Int.MIN_VALUE); f.run(1); eq(0, f.reg(0)); f.flags(CortexM4.Z or CortexM4.C)
    }
    test("ASRS immediate zero means 32") {
        val f = Fixture(0x1008); f.reg(1, Int.MIN_VALUE); f.run(1); eq(-1, f.reg(0)); f.flags(CortexM4.N or CortexM4.C)
    }
    test("LSLS immediate zero preserves carry") {
        val f = Fixture(0x0008); f.reg(1, 1); f.cpu.apsr = CortexM4.C; f.run(1); eq(1, f.reg(0)); yes(f.cpu.c)
    }
    test("RRXS carry in and out") {
        val f = Fixture(0xea5f, 0x0031); f.reg(1, 2); f.cpu.apsr = CortexM4.C; f.run(1)
        eq(0x80000001.toInt(), f.reg(0)); f.flags(CortexM4.N)
    }
}

private fun wide() {
    test("MOVW MOVT compose constant") {
        val f = Fixture(0xf241, 0x2034, 0xf6ca, 0x30cd); f.run(2); eq(0xabcd1234.toInt(), f.reg(0))
    }
    test("modified immediate replication") {
        val f = Fixture(0xf04f, 0x10ff, 0xf04f, 0x22ab, 0xf04f, 0x335a); f.run(3)
        eq(0x00ff00ff, f.reg(0)); eq(0xab00ab00.toInt(), f.reg(2)); eq(0x5a5a5a5a, f.reg(3))
    }
    test("MOVS modified immediate rotation sets carry") {
        val f = Fixture(0xf05f, 0x4000); f.run(1); eq(Int.MIN_VALUE, f.reg(0)); f.flags(CortexM4.N or CortexM4.C)
    }
    test("ADD.W without S preserves flags") {
        val f = Fixture(0xeb00, 0x0081); f.reg(0, 3); f.reg(1, 4); f.cpu.apsr = CortexM4.Z or CortexM4.C
        f.run(1); eq(19, f.reg(0)); f.flags(CortexM4.Z or CortexM4.C)
    }
    test("ADDW SUBW ADR use plain immediates") {
        val f = Fixture(0xf200, 0x1023, 0xf2a0, 0x1023); f.reg(0, 100); f.run(1); eq(391, f.reg(0)); f.run(1); eq(100, f.reg(0))
    }
    test("UBFX SBFX and BFI") {
        val f = Fixture(0xf3c1, 0x2007, 0xf341, 0x2207, 0xf361, 0x200f)
        f.reg(1, 0x1234ab78); f.run(2); eq(0xab, f.reg(0)); eq(-85, f.reg(2)); f.run(1); eq(0x78ab, f.reg(0))
    }
    test("CLZ RBIT REV") {
        val f = Fixture(0xfab1, 0xf081, 0xfa91, 0xf2a1, 0xba0b); f.reg(1, 0x00010002); f.run(3)
        eq(15, f.reg(0)); eq(0x40008000, f.reg(2)); eq(0x02000100, f.reg(3))
    }
    test("wide UXTB SXTH rotated UXTAB") {
        val f = Fixture(0xfa5f, 0xf089, 0xfa0f, 0xf189, 0xfa52, 0xf399)
        f.reg(9, 0x1234ff80); f.reg(2, 5); f.run(3)
        eq(128, f.reg(0)); eq(-128, f.reg(1)); eq(260, f.reg(3))
    }
    test("UDIV SDIV overflow and zero") {
        val f = Fixture(0xfbb1, 0xf0f2, 0xfb91, 0xf3f2); f.reg(1, -7); f.reg(2, 2); f.run(2)
        eq(0x7ffffffc, f.reg(0)); eq(-3, f.reg(3))
        f.cpu.pc = 0x104; f.reg(1, Int.MIN_VALUE); f.reg(2, -1); f.run(1); eq(Int.MIN_VALUE, f.reg(3))
        f.cpu.pc = 0x104; f.reg(2, 0); f.run(1); eq(0, f.reg(3))
    }
    test("UMULL SMULL MLA MLS") {
        val f = Fixture(0xfba1, 0x2300, 0xfb81, 0x4500, 0xfb01, 0x2600, 0xfb01, 0x2710)
        f.reg(1, -1); f.reg(0, 2); f.run(1); eq(-2, f.reg(2)); eq(1, f.reg(3))
        f.run(1); eq(-2, f.reg(4)); eq(-1, f.reg(5)); f.run(1); eq(-4, f.reg(6)); f.run(1); eq(0, f.reg(7))
    }
}

private fun memory() {
    test("bus little endian overlapping widths and strict bounds") {
        val f = Fixture(); f.bus.write32(0x401, 0x12345678); eq(0x78, f.bus.read8(0x401)); eq(0x3456, f.bus.read16(0x402))
        f.bus.write8(0x402, 0xab); eq(0x1234ab78, f.bus.read32(0x401))
        stops("unmapped") { f.bus.read32(0xfffe) }; stops("unmapped") { f.bus.read32(-2) }
    }
    test("STR LDR STRB LDRB STRH LDRH immediate") {
        val f = Fixture(0x6048, 0x684a, 0x7048, 0x784b, 0x8048, 0x884c)
        f.reg(1, 0x400); f.reg(0, 0x12345678); f.run(6)
        eq(0x12345678, f.reg(2)); eq(0x78, f.reg(3)); eq(0x5678, f.reg(4))
    }
    test("register offset signed byte and halfword") {
        val f = Fixture(0x5688, 0x5e8b); f.reg(1, 0x400); f.reg(2, 3); f.bus.write16(0x403, 0x80ff); f.run(2)
        eq(-1, f.reg(0)); eq(-32513, f.reg(3))
    }
    test("narrow immediate transfers use all five offset bits") {
        val f = Fixture(0x67c8, 0x6fca, 0x77c8, 0x7fcb, 0x87c8, 0x8fcc)
        f.reg(1, 0x400); f.reg(0, 0x12345678); f.run(6)
        eq(0x12345678, f.bus.read32(0x47c)); eq(0x5678, f.bus.read16(0x43e)); eq(0x78, f.bus.read8(0x41f))
        eq(0x12345678, f.reg(2)); eq(0x78, f.reg(3)); eq(0x5678, f.reg(4))
    }
    test("PC-relative LDR aligns PC plus four") {
        val f = Fixture(0xbf00, 0x4801); f.bus.write32(0x108, 0x12345678); f.run(2); eq(0x12345678, f.reg(0))
    }
    test("SP-relative loads and ADR") {
        val f = Fixture(0x9002, 0x9902, 0xaa02, 0xa302); f.reg(0, 42); f.run(4)
        eq(42, f.reg(1)); eq(0xf008, f.reg(2)); eq(0x110, f.reg(3))
    }
    test("PUSH POP including LR and PC") {
        val f = Fixture(0xb513, 0xbd13); f.reg(0, 10); f.reg(1, 11); f.reg(4, 14); f.reg(14, 0x301); f.run(1)
        eq(0xeff0, f.reg(13)); eq(10, f.bus.read32(0xeff0)); eq(0x301, f.bus.read32(0xeffc))
        f.reg(0, 0); f.run(1); eq(10, f.reg(0)); eq(0xf000, f.reg(13)); eq(0x300, f.cpu.pc)
    }
    test("STMIA LDMIA register order and writeback") {
        val f = Fixture(0xc106, 0xc90c); f.reg(1, 0x400); f.reg(2, 0x2222); f.run(1)
        eq(0x400, f.bus.read32(0x400)); eq(0x2222, f.bus.read32(0x404)); eq(0x408, f.reg(1))
        f.reg(1, 0x400); f.run(1); eq(0x400, f.reg(2)); eq(0x2222, f.reg(3)); eq(0x408, f.reg(1))
    }
    test("wide pre/post index load/store and negative literal") {
        val f = Fixture(0xf841, 0x0f04, 0xf851, 0x2b04, 0xf85f, 0x3010)
        f.reg(1, 0x400); f.reg(0, 123); f.run(1); eq(0x404, f.reg(1)); f.run(1); eq(123, f.reg(2)); eq(0x408, f.reg(1))
        f.bus.write32(0xfc, 456); f.run(1); eq(456, f.reg(3))
    }
    test("STRD LDRD and wide push/pop") {
        val f = Fixture(0xe9c2, 0x0102, 0xe9d2, 0x3402, 0xe92d, 0x4100, 0xe8bd, 0x4100)
        f.reg(0, 101); f.reg(1, 202); f.reg(2, 0x400); f.reg(8, 808); f.reg(14, 0x301); f.run(4)
        eq(101, f.reg(3)); eq(202, f.reg(4)); eq(808, f.reg(8)); eq(0xf000, f.reg(13))
    }
    test("LDREX STREX success and failed second store") {
        val f = Fixture(0xe851, 0x0f00, 0xe841, 0x0200, 0xe841, 0x0300)
        f.reg(1, 0x400); f.bus.write32(0x400, 21); f.run(1); eq(21, f.reg(0)); f.reg(0, 22); f.run(2)
        eq(0, f.reg(2)); eq(1, f.reg(3)); eq(22, f.bus.read32(0x400))
    }
    test("unknown MMIO fails with PC and encoding") {
        val f = Fixture(0x6808); f.reg(1, 0x40000000)
        stops("pc=0x00000100 encoding=6808: unmapped memory address=0x40000000") { f.run(1) }
        eq(0x100, f.cpu.pc)
    }
    test("unimplemented instruction fails loudly") {
        val f = Fixture(0xde00); stops("encoding=de00: unimplemented") { f.run(1) }
        val g = Fixture(0xee00, 0x0000); stops("encoding=ee00 0000: unimplemented") { g.run(1) }
    }
}

private fun branches() {
    test("all condition codes from NZCV") {
        val f = Fixture()
        f.cpu.apsr = CortexM4.N or CortexM4.C
        val expected = booleanArrayOf(false, true, true, false, true, false, false, true, true, false, false, true, false, true, true, false)
        expected.forEachIndexed { i, value -> yes(f.cpu.condition(i) == value) }
    }
    test("ITE executes then/else and suppresses implicit flags") {
        val f = Fixture(0x2800, 0xbf0c, 0x2107, 0x2109); f.run(4); eq(7, f.reg(1)); yes(f.cpu.z); eq(0, f.cpu.itState)
        val g = Fixture(0x2800, 0xbf0c, 0x2107, 0x2109); g.reg(0, 1); g.run(4); eq(9, g.reg(1)); yes(!g.cpu.z)
    }
    test("IT skips entire wide instruction") {
        val f = Fixture(0xbf08, 0xf241, 0x2034, 0x220a); f.run(3); eq(0, f.reg(0)); eq(10, f.reg(2)); eq(0x108, f.cpu.pc)
    }
    test("backward conditional loop and CBZ CBNZ") {
        val f = Fixture(0x2003, 0x3801, 0xd1fd, 0xb100, 0x2101, 0x2102); f.run(9); eq(2, f.reg(1)); eq(0x10c, f.cpu.pc)
    }
    test("BL and BX return use next instruction plus Thumb bit") {
        val f = Fixture(0xf000, 0xf802, 0x2007, 0xe001, 0x2108, 0x4770); f.run(5)
        eq(0x105, f.reg(14)); eq(7, f.reg(0)); eq(8, f.reg(1)); eq(0x10c, f.cpu.pc)
    }
    test("BLX register and reject ARM target") {
        val f = Fixture(0x4788); f.reg(1, 0x301); f.run(1); eq(0x103, f.reg(14)); eq(0x300, f.cpu.pc)
        val g = Fixture(0x4708); g.reg(1, 0x300); stops("non-Thumb branch") { g.run(1) }
    }
    test("B.W sign-extended negative displacement") {
        val f = Fixture(0xf7ff, 0xbffe); f.run(1); eq(0x100, f.cpu.pc)
    }
    test("B.cond.W PC path") {
        val f = Fixture(0xf000, 0x8002); f.cpu.apsr = CortexM4.Z; f.run(1); eq(0x108, f.cpu.pc)
    }
}

private fun exceptions() {
    test("SVC basic exception frame and return") {
        val f = Fixture(0xdf00, 0x2307); f.bus.write32(11 * 4, 0x301); f.bus.write16(0x300, 0x4770)
        f.reg(0, 42); f.run(1); eq(11, f.cpu.ipsr); eq(0xfffffff9.toInt(), f.reg(14))
        eq(0xefe0, f.cpu.msp); eq(42, f.bus.read32(0xefe0)); eq(0x102, f.bus.read32(0xeff8))
        f.run(2); eq(0, f.cpu.ipsr); eq(0xf000, f.cpu.msp); eq(7, f.reg(3))
    }
    test("PSP exception return and alignment padding") {
        val f = Fixture(0xdf00); f.cpu.psp = 0xe004; f.cpu.control = 2
        f.bus.write32(44, 0x301); f.bus.write16(0x300, 0x4770); f.run(1)
        eq(0xfffffffD.toInt(), f.reg(14)); eq(0xdfe0, f.cpu.psp); eq(0xf000, f.cpu.msp)
        yes(f.bus.read32(0xdffc) and 0x200 != 0); f.run(1); eq(0xe004, f.cpu.psp); eq(2, f.cpu.control)
    }
    test("SysTick reload countflag clear-on-read and interrupt") {
        val f = Fixture(0xbf00, 0xbf00, 0xbf00, 0xbf00); f.bus.write32(60, 0x301); f.bus.write16(0x300, 0x4770)
        f.system.write(0x14, 4, 2); f.system.write(0x10, 4, 7); f.run(3)
        eq(1, f.system.systickWraps.toInt()); yes(f.system.read(0x10, 4) and 0x10000 != 0); eq(7, f.system.read(0x10, 4))
        f.run(1); eq(0, f.cpu.ipsr); eq(1, f.cpu.exceptionEntries.toInt()); eq(0x106, f.cpu.pc)
    }
    test("NVIC masks, enable clear and priority preemption") {
        val s = SystemControl(); s.pend(16); eq(0, s.nextException(0, 0, 0, 0)); s.write(0x100, 4, 1)
        s.write(0x400, 1, 0x60); eq(16, s.nextException(0, 0, 0, 0)); eq(0, s.nextException(0, 1, 0, 0))
        eq(0, s.nextException(0, 0, 0x50, 0)); eq(16, s.nextException(0, 0, 0x70, 0))
        s.write(0x180, 4, 1); eq(0, s.nextException(0, 0, 0, 0)); s.pend(2); eq(2, s.nextException(0, 1, 0x10, 1))
    }
    test("MRS MSR MSP PSP BASEPRI and CONTROL") {
        val f = Fixture(0xf380, 0x8809, 0xf3ef, 0x8109, 0xf382, 0x8811)
        f.reg(0, 0xe001); f.reg(2, 0x5f); f.run(3); eq(0xe000, f.reg(1)); eq(0xe000, f.cpu.psp); eq(0x50, f.cpu.basepri)
    }
    test("CPSID CPSIE") { val f = Fixture(0xb672, 0xb662); f.run(1); eq(1, f.cpu.primask); f.run(1); eq(0, f.cpu.primask) }
}

private fun boot() {
    test("hand-written Thumb boot trace copies data, clears BSS and takes first tick") {
        // MOVS r0,#3; SUBS r0,#1; BNE; STR r1,[r2]; STR r0,[r2,#4]; B .
        val f = Fixture(0x2003, 0x3801, 0xd1fd, 0x6011, 0x6050, 0xe7fe)
        f.reg(1, 0x12345678); f.reg(2, 0x400); f.bus.write32(0x404, -1)
        f.bus.write32(60, 0x301); f.bus.write16(0x300, 0x4770)
        f.system.write(0x14, 4, 15); f.system.write(0x10, 4, 7)
        f.run(40); eq(0x12345678, f.bus.read32(0x400)); eq(0, f.bus.read32(0x404))
        eq(0x10a, f.cpu.pc); yes(f.cpu.exceptionEntries > 0)
    }
    test("STM32 memory aliases share storage and flash rejects writes") {
        val bytes = ByteArray(256); bytes[3] = 0x20; bytes[4] = 0x09; bytes[7] = 0x08
        val b = Stm32wb55(bytes); b.bus.write32(0x20030000, 42); eq(42, b.bus.read32(0x10000000))
        eq(b.bus.read32(4), b.bus.read32(0x08000004)); stops("read-only") { b.bus.write32(0, 0) }
    }
}

private fun peripherals() {
    test("GPIO pulls, output latch, BSRR set priority and BRR") {
        val g = Gpio(2); g.write(0xc, 4, 1 shl 12); eq(1 shl 6, g.read(0x10, 4))
        g.write(0, 4, 1); g.write(0x18, 4, 0x00010001); eq(1, g.read(0x14, 4)); eq(65, g.read(0x10, 4))
        g.write(0x28, 4, 1); eq(0, g.read(0x14, 4)); stops("unimplemented GPIO") { g.read(0x30, 4) }
    }
    test("I2C disconnected address receives NACK and STOP, no false ACK") {
        val i = EmptyI2c(); i.write(0, 4, 1); i.write(4, 4, 0x02022060)
        eq(0x31, i.read(0x18, 4)); i.write(0x1c, 4, 0x30); eq(1, i.read(0x18, 4))
        stops("without a slave") { i.write(0x28, 4, 123) }
    }
    test("RCC oscillator readiness follows enable state") {
        val r = Rcc(); r.write(0, 4, 0x01010001); eq(0x03030003, r.read(0, 4))
        r.write(0, 4, 0); eq(0, r.read(0, 4)); r.write(8, 4, 3); eq(15, r.read(8, 4))
        stops("unimplemented RCC") { r.read(0x34, 4) }
    }
    test("RTC key lock, initialization and shadow synchronization") {
        val r = RtcBackup(); r.write(8, 4, 64); eq(0, r.read(8, 4))
        r.write(0x24, 4, 0xca); r.write(0x24, 4, 0x53); r.write(0xc, 4, 0x80)
        yes(r.read(0xc, 4) and 0x40 != 0); r.write(0x10, 4, 0x007f00ff); r.write(0xc, 4, 0)
        eq(0, r.read(0xc, 4) and 0x20); yes(r.read(0xc, 4) and 0x20 != 0)
        r.write(0x24, 4, 255); r.write(8, 4, 64); eq(0, r.read(8, 4))
    }
    test("USART captures TX and reports enable acknowledgements") {
        val u = BootUsart(); eq(0xc0, u.read(0x1c, 4)); u.write(0, 4, 13)
        eq(0x006000c0, u.read(0x1c, 4)); u.write(0x28, 1, 65); eq(65, u.output.single())
        stops("no received data") { u.read(0x24, 4) }; stops("not implemented") { u.write(0, 4, 0x89) }
    }
    test("DWT counts virtual cycles only while enabled") {
        val s = SystemControl(); val d = Dwt(s); s.demcr = 1 shl 24; d.write(0, 4, 1)
        repeat(7) { s.tick() }; eq(7, d.read(4, 4)); d.write(0, 4, 0); repeat(3) { s.tick() }; eq(7, d.read(4, 4))
    }
    test("MPU read-only guard and higher region overrides") {
        val f = Fixture(); val s = f.system
        s.write(0xd9c, 4, 0x410); s.write(0xda0, 4, 0x06000009); s.write(0xd94, 4, 5)
        eq(0, f.bus.read32(0x400)); stops("MPU access denied region=0") { f.bus.write32(0x400, 1) }
        s.write(0xd9c, 4, 0x411); s.write(0xda0, 4, 0x03000009); f.bus.write32(0x400, 2); eq(2, f.bus.read32(0x400))
    }
    test("MPU disabled subregion falls back and XN forbids fetch") {
        val f = Fixture(0xbf00); val s = f.system
        s.write(0xd9c, 4, 0x410); s.write(0xda0, 4, 0x0000010f); s.write(0xd94, 4, 5)
        f.bus.write32(0x400, 1); stops("MPU access denied") { f.bus.read32(0x420) }
        s.write(0xd9c, 4, 0x110); s.write(0xda0, 4, 0x13000009); stops("execute=true") { f.run(1) }
    }
    test("MPU vector lookup and PPB bypass no-access region") {
        val f = Fixture(0xdf00); f.bus.write32(44, 0x301); f.bus.write16(0x300, 0x4770)
        f.system.write(0xd9c, 4, 0x10); f.system.write(0xda0, 4, 0x0d); f.system.write(0xd94, 4, 5)
        stops("MPU access denied") { f.bus.read32(44) }; f.run(2); eq(0, f.cpu.ipsr)
        eq(0x410fc241, f.bus.read32(0xe000ed00.toInt()))
    }
    test("reset disables MPU before reading reset vectors") {
        val f = Fixture(); f.system.write(0xd9c, 4, 0x10); f.system.write(0xda0, 4, 0x0d)
        f.system.write(0xd94, 4, 5); f.cpu.reset(); eq(0x100, f.cpu.pc); eq(0xf000, f.cpu.msp)
    }
    test("CCR traps unaligned narrow LDR and divide by zero") {
        val f = Fixture(0x6808); f.reg(1, 0x401); f.system.ccr = 0x208; stops("unaligned transfer") { f.run(1) }
        val g = Fixture(0xfbb1, 0xf0f2); g.system.ccr = 0x210; stops("division by zero trap") { g.run(1) }
    }
    test("nested IRQ frame restores previous handler then thread") {
        val f = Fixture(0xbf00); f.bus.write32(16 * 4, 0x301); f.bus.write32(17 * 4, 0x401)
        f.bus.write16(0x300, 0xbf00); f.bus.write16(0x302, 0x4770); f.bus.write16(0x400, 0x4770)
        f.system.write(0x100, 4, 3); f.system.write(0x400, 1, 0x60); f.system.write(0x401, 1, 0x20)
        f.system.pend(16); f.run(1); eq(16, f.cpu.ipsr); f.system.pend(17); f.run(1)
        eq(16, f.cpu.ipsr); eq(0x302, f.cpu.pc); f.run(1); eq(0, f.cpu.ipsr); eq(0xf000, f.cpu.msp)
    }
    test("exception restores interrupted IT state") {
        val f = Fixture(0xbf08, 0x2007); f.cpu.apsr = CortexM4.Z; f.bus.write32(60, 0x301); f.bus.write16(0x300, 0x4770)
        f.run(1); f.system.pend(15); f.run(1); eq(8, f.cpu.itState); f.run(1); eq(7, f.reg(0)); eq(0, f.cpu.itState)
    }
    test("BASEPRI_MAX only strengthens a nonzero mask") {
        val f = Fixture(0xf380, 0x8812); f.reg(0, 0x60); f.run(1); eq(0x60, f.cpu.basepri)
        f.cpu.pc = 0x100; f.reg(0, 0x80); f.run(1); eq(0x60, f.cpu.basepri)
        f.cpu.pc = 0x100; f.reg(0, 0x20); f.run(1); eq(0x20, f.cpu.basepri)
    }
}

fun main() {
    arithmetic(); shifts(); wide(); memory(); branches(); exceptions(); boot(); peripherals()
    println("Core tests: $passed passed, $failed failed")
    check(failed == 0) { "$failed core tests failed" }
}
