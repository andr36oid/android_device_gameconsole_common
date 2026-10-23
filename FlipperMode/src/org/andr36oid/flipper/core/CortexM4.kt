package org.andr36oid.flipper.core

// ARM DDI 0403D, A5.2/A5.3 (encoding), A7.4/A7.7 (operations), B1.4/B1.5
// (special registers and exceptions). Unsupported encodings stop at the fetch address.
class CortexM4(val bus: MemoryBus, val system: SystemControl = SystemControl()) {
    private val r = IntArray(16)
    var apsr = 0
    var msp = 0
    var psp = 0
    var control = 0
    var primask = 0
    var basepri = 0
    var faultmask = 0
    var ipsr = 0
        private set
    var itState = 0
        private set
    var pc: Int
        get() = r[15]
        set(value) { r[15] = value and -2 }
    var instructions = 0L
        private set
    var exceptionEntries = 0L
        private set
    var sleeping = false
        private set
    var onException: ((Int) -> Unit)? = null
    private var address = 0
    private var first = 0
    private var second = 0
    private var exclusiveAddress: Int? = null
    private val active = ArrayList<Int>()
    val n: Boolean get() = apsr < 0
    val z: Boolean get() = apsr and Z != 0
    val c: Boolean get() = apsr and C != 0
    val v: Boolean get() = apsr and V != 0

    init {
        bus.accessGuard = { addr, size, write ->
            if (system.ccr and 8 != 0 && addr and (size - 1) != 0) stop("unaligned transfer address=${hex(addr)} size=$size")
            system.checkMpu(addr, size, write, false, ipsr != 0 || control and 1 == 0, ipsr)
        }
    }

    companion object {
        const val N = Int.MIN_VALUE
        const val Z = 0x40000000
        const val C = 0x20000000
        const val V = 0x10000000
        private fun sign(value: Int, bits: Int) = (value shl (32 - bits)) shr (32 - bits)
    }

    fun readRegister(index: Int): Int = when (index) {
        13 -> if (ipsr == 0 && control and 2 != 0) psp else msp
        15 -> address + 4
        else -> r[index]
    }

    fun writeRegister(index: Int, value: Int) {
        when (index) {
            13 -> if (ipsr == 0 && control and 2 != 0) psp = value and -4 else msp = value and -4
            15 -> branch(value)
            else -> r[index] = value
        }
    }

    fun reset(vector: Int = 0) {
        system.reset()
        r.fill(0)
        apsr = 0; control = 0; primask = 0; basepri = 0; faultmask = 0; ipsr = 0; itState = 0
        psp = 0; msp = bus.read32(vector) and -4
        instructions = 0; exceptionEntries = 0; sleeping = false; active.clear(); exclusiveAddress = null
        system.vtor = vector
        branch(bus.read32(vector + 4))
    }

    private fun stop(reason: String): Nothing = throw EmulationStop(reason)

    private fun branch(target: Int) {
        if (ipsr != 0 && target ushr 28 == 15) {
            exceptionReturn(target)
            return
        }
        if (target and 1 == 0) stop("non-Thumb branch target=${hex(target)}")
        pc = target
    }

    private fun setNZ(value: Int) {
        apsr = (apsr and (N or Z).inv()) or (value and N) or (if (value == 0) Z else 0)
    }

    private fun setCarry(value: Boolean) { apsr = (apsr and C.inv()) or if (value) C else 0 }

    private fun add(a: Int, b: Int, carry: Boolean, flags: Boolean): Int {
        val sum = (a.toLong() and 0xffffffffL) + (b.toLong() and 0xffffffffL) + if (carry) 1 else 0
        val value = sum.toInt()
        if (flags) {
            setNZ(value)
            setCarry(sum ushr 32 != 0L)
            apsr = (apsr and V.inv()) or (if ((a xor value) and (b xor value) < 0) V else 0)
        }
        return value
    }

    // A7.4.2: register shifts use the entire low byte, including 32 and values above 32.
    private fun shift(value: Int, type: Int, amount: Int, flags: Boolean, immediate: Boolean = false): Int {
        var count = amount
        if (immediate && count == 0 && (type == 1 || type == 2)) count = 32
        var carry = c
        val result: Int
        if (type == 3 && immediate && count == 0) {
            result = (if (c) N else 0) or (value ushr 1)
            carry = value and 1 != 0
        } else if (count == 0) result = value
        else when (type) {
            0 -> {
                carry = count <= 32 && (value ushr (32 - count)) and 1 != 0
                result = if (count < 32) value shl count else 0
            }
            1 -> {
                carry = count <= 32 && (value ushr (count - 1)) and 1 != 0
                result = if (count < 32) value ushr count else 0
            }
            2 -> {
                carry = if (count >= 32) value < 0 else value ushr (count - 1) and 1 != 0
                result = value shr minOf(count, 31)
            }
            else -> {
                result = Integer.rotateRight(value, count and 31)
                carry = result < 0
            }
        }
        if (flags) setCarry(carry)
        return result
    }

    fun condition(code: Int): Boolean = when (code) {
        0 -> z; 1 -> !z; 2 -> c; 3 -> !c; 4 -> n; 5 -> !n; 6 -> v; 7 -> !v
        8 -> c && !z; 9 -> !c || z; 10 -> n == v; 11 -> n != v
        12 -> !z && n == v; 13 -> z || n != v; 14 -> true
        else -> false
    }

    private fun xpsr(): Int = (apsr and 0xf8000000.toInt()) or 0x01000000 or ipsr or
        ((itState and 3) shl 25) or ((itState and 0xfc) shl 8)

    // B1.5.6/B1.5.8: basic eight-word frames, MSP/PSP selection and STKALIGN padding.
    private fun enterException(number: Int) {
        val oldSp = readRegister(13)
        val align = system.ccr and 0x200 != 0 && oldSp and 4 != 0
        val sp = (oldSp - 32) and (if (align) -8 else -4)
        val frame = intArrayOf(r[0], r[1], r[2], r[3], r[12], r[14], pc,
            xpsr() or (if (align) 0x200 else 0))
        for (i in frame.indices) bus.write32(sp + i * 4, frame[i])
        val thread = ipsr == 0
        val process = thread && control and 2 != 0
        writeRegister(13, sp)
        r[14] = if (!thread) 0xfffffff1.toInt() else if (process) 0xfffffffd.toInt() else 0xfffffff9.toInt()
        active.add(ipsr)
        ipsr = number; itState = 0; sleeping = false; exclusiveAddress = null
        system.acknowledge(number)
        branch(bus.readVector32(system.vtor + number * 4))
        exceptionEntries++
        onException?.invoke(number)
    }

    private fun exceptionReturn(token: Int) {
        if (token != 0xfffffff1.toInt() && token != 0xfffffff9.toInt() && token != 0xfffffffd.toInt())
            stop("unsupported EXC_RETURN=${hex(token)} (extended FP frames are not implemented)")
        if (active.isEmpty()) stop("exception return without active exception")
        val process = token and 4 != 0
        val sp = if (process) psp else msp
        val frame = IntArray(8) { bus.read32(sp + it * 4) }
        if (frame[7] and 0x01000000 == 0) stop("exception return with T=0")
        val returnIpsr = frame[7] and 0x1ff
        val previous = active.last()
        if (returnIpsr != previous || (token and 8 != 0) != (returnIpsr == 0)) stop("inconsistent exception return")
        system.complete(ipsr)
        active.removeAt(active.lastIndex)
        for (i in 0..3) r[i] = frame[i]
        r[12] = frame[4]; r[14] = frame[5]; pc = frame[6]
        apsr = frame[7] and 0xf8000000.toInt()
        itState = ((frame[7] ushr 25) and 3) or ((frame[7] ushr 8) and 0xfc)
        ipsr = returnIpsr
        if (ipsr == 0) control = (control and 2.inv()) or if (process) 2 else 0
        val restored = sp + 32 + if (frame[7] and 0x200 != 0) 4 else 0
        if (process) psp = restored else msp = restored
        exclusiveAddress = null
    }

    fun step() {
        address = pc; first = 0; second = 0
        try {
            val pending = system.nextException(ipsr, primask, basepri, faultmask)
            if (pending != 0) { enterException(pending); address = pc }
            if (sleeping && system.nextException(ipsr, 0, basepri, faultmask) != 0) sleeping = false
            if (sleeping) { system.tick(); return }
            system.checkMpu(address, 2, false, true, ipsr != 0 || control and 1 == 0, ipsr)
            first = bus.read16(address)
            val wide = first ushr 11 >= 0x1d
            if (wide) {
                system.checkMpu(address + 2, 2, false, true, ipsr != 0 || control and 1 == 0, ipsr)
                second = bus.read16(address + 2)
            }
            pc = address + if (wide) 4 else 2
            val oldIt = itState
            if (oldIt == 0 || condition(oldIt ushr 4)) {
                if (wide) execute32(first, second) else execute16(first, oldIt == 0)
            }
            if (oldIt != 0 && itState == oldIt) {
                itState = if (oldIt and 7 == 0) 0 else (oldIt and 0xe0) or ((oldIt shl 1) and 0x1f)
            }
            instructions++
            system.tick()
        } catch (e: EmulationStop) {
            pc = address
            throw EmulationStop("pc=${hex(address)} encoding=${first.toString(16).padStart(4, '0')}" +
                (if (first ushr 11 >= 0x1d) " ${second.toString(16).padStart(4, '0')}" else "") + ": ${e.message}")
        }
    }

    private fun execute16(op: Int, implicitFlags: Boolean) {
        val d = op and 7
        val m = (op ushr 3) and 7
        val n = (op ushr 6) and 7
        when {
            op and 0xe000 == 0 && op and 0x1800 != 0x1800 -> {
                val result = shift(r[m], op ushr 11, (op ushr 6) and 31, implicitFlags, true)
                r[d] = result; if (implicitFlags) setNZ(result)
            }
            op and 0xf800 == 0x1800 -> {
                val b = if (op and 0x400 != 0) n else r[n]
                r[d] = if (op and 0x200 != 0) add(r[m], b.inv(), true, implicitFlags)
                    else add(r[m], b, false, implicitFlags)
            }
            op and 0xe000 == 0x2000 -> {
                val rd = (op ushr 8) and 7; val imm = op and 255
                when ((op ushr 11) and 3) {
                    0 -> { r[rd] = imm; if (implicitFlags) setNZ(imm) }
                    1 -> add(r[rd], imm.inv(), true, true)
                    2 -> r[rd] = add(r[rd], imm, false, implicitFlags)
                    3 -> r[rd] = add(r[rd], imm.inv(), true, implicitFlags)
                }
            }
            op and 0xfc00 == 0x4000 -> {
                val a = r[d]; val b = r[m]; val kind = (op ushr 6) and 15
                val result = when (kind) {
                    0 -> a and b; 1 -> a xor b
                    2 -> shift(a, 0, b and 255, implicitFlags)
                    3 -> shift(a, 1, b and 255, implicitFlags)
                    4 -> shift(a, 2, b and 255, implicitFlags)
                    5 -> add(a, b, c, implicitFlags)
                    6 -> add(a, b.inv(), c, implicitFlags)
                    7 -> shift(a, 3, b and 255, implicitFlags)
                    8 -> a and b; 9 -> add(0, b.inv(), true, implicitFlags)
                    10 -> add(a, b.inv(), true, true); 11 -> add(a, b, false, true)
                    12 -> a or b; 13 -> a * b; 14 -> a and b.inv(); else -> b.inv()
                }
                if (kind == 8) setNZ(result)
                else if (kind != 10 && kind != 11) {
                    r[d] = result
                    if (implicitFlags) setNZ(result)
                }
            }
            op and 0xfc00 == 0x4400 -> {
                val rd = d or ((op ushr 4) and 8); val rm = (op ushr 3) and 15
                when ((op ushr 8) and 3) {
                    0 -> { val value = readRegister(rd) + readRegister(rm)
                        if (rd == 15) pc = value else writeRegister(rd, value) }
                    1 -> add(readRegister(rd), readRegister(rm).inv(), true, true)
                    2 -> { val value = readRegister(rm)
                        if (rd == 15) pc = value else writeRegister(rd, value) }
                    3 -> { val target = readRegister(rm); if (op and 0x80 != 0) r[14] = pc or 1; branch(target) }
                }
            }
            op and 0xf800 == 0x4800 -> r[(op ushr 8) and 7] = bus.read32(((address + 4) and -4) + (op and 255) * 4)
            op and 0xf000 == 0x5000 -> {
                val addr = r[m] + r[n]
                when ((op ushr 9) and 7) {
                    0 -> bus.write32(addr, r[d]); 1 -> bus.write16(addr, r[d]); 2 -> bus.write8(addr, r[d])
                    3 -> r[d] = sign(bus.read8(addr), 8); 4 -> r[d] = bus.read32(addr)
                    5 -> r[d] = bus.read16(addr); 6 -> r[d] = bus.read8(addr); 7 -> r[d] = sign(bus.read16(addr), 16)
                }
            }
            op and 0xe000 == 0x6000 -> {
                val byte = op and 0x1000 != 0
                val addr = r[m] + ((op ushr 6) and 31) * if (byte) 1 else 4
                if (op and 0x800 != 0) r[d] = bus.read(addr, if (byte) 1 else 4)
                else bus.write(addr, if (byte) 1 else 4, r[d])
            }
            op and 0xf000 == 0x8000 -> {
                val addr = r[m] + ((op ushr 6) and 31) * 2
                if (op and 0x800 != 0) r[d] = bus.read16(addr) else bus.write16(addr, r[d])
            }
            op and 0xf000 == 0x9000 -> {
                val addr = readRegister(13) + (op and 255) * 4; val rt = (op ushr 8) and 7
                if (op and 0x800 != 0) r[rt] = bus.read32(addr) else bus.write32(addr, r[rt])
            }
            op and 0xf000 == 0xa000 -> r[(op ushr 8) and 7] =
                (if (op and 0x800 != 0) readRegister(13) else (address + 4) and -4) + (op and 255) * 4
            op and 0xff00 == 0xb000 -> writeRegister(13, readRegister(13) + (op and 127) * if (op and 128 == 0) 4 else -4)
            op and 0xf500 == 0xb100 -> {
                val nonzero = op and 0x800 != 0
                if ((r[d] != 0) == nonzero) pc = address + 4 + ((op ushr 3) and 31) * 2 + ((op ushr 9) and 1) * 64
            }
            op and 0xff00 == 0xb200 -> r[d] = when ((op ushr 6) and 3) {
                0 -> sign(r[m], 16); 1 -> sign(r[m], 8); 2 -> r[m] and 65535; else -> r[m] and 255
            }
            op and 0xfe00 == 0xb400 -> {
                val list = (op and 255) or if (op and 256 != 0) 1 shl 14 else 0
                multiple(13, list, false, true, true)
            }
            op and 0xfe00 == 0xbc00 -> {
                val list = (op and 255) or if (op and 256 != 0) 1 shl 15 else 0
                multiple(13, list, true, false, true)
            }
            op == 0xb662 -> primask = 0
            op == 0xb672 -> primask = 1
            op == 0xb661 -> faultmask = 0
            op == 0xb671 -> faultmask = 1
            op and 0xff00 == 0xba00 -> r[d] = when ((op ushr 6) and 3) {
                0 -> Integer.reverseBytes(r[m]); 1 -> ((r[m] and 0x00ff00ff) shl 8) or ((r[m] ushr 8) and 0x00ff00ff)
                3 -> sign(((r[m] and 255) shl 8) or ((r[m] ushr 8) and 255), 16)
                else -> stop("unimplemented instruction")
            }
            op and 0xff00 == 0xbe00 -> stop("BKPT immediate=${op and 255}")
            op and 0xff00 == 0xbf00 -> {
                if (op and 15 != 0) {
                    if (itState != 0 || (op ushr 4) and 15 >= 14) stop("invalid IT block")
                    itState = op and 255
                } else when ((op ushr 4) and 15) {
                    0, 1 -> Unit
                    3 -> sleeping = true
                    else -> stop("unimplemented hint")
                }
            }
            op and 0xf000 == 0xc000 -> multiple((op ushr 8) and 7, op and 255, op and 0x800 != 0, false,
                op and 0x800 == 0 || op and (1 shl ((op ushr 8) and 7)) == 0)
            op and 0xff00 == 0xdf00 -> {
                if (primask != 0 || faultmask != 0 || (basepri != 0 && system.priorityOf(11) >= basepri))
                    stop("SVC escalation under interrupt mask is not implemented")
                enterException(11)
            }
            op and 0xf000 == 0xd000 && op and 0xf00 < 0xe00 ->
                if (condition((op ushr 8) and 15)) pc = address + 4 + sign(op and 255, 8) * 2
            op and 0xf800 == 0xe000 -> pc = address + 4 + sign(op and 0x7ff, 11) * 2
            else -> stop("unimplemented instruction")
        }
    }

    private fun multiple(rn: Int, list: Int, load: Boolean, decrement: Boolean, writeback: Boolean) {
        if (list == 0) stop("empty register list")
        val base = readRegister(rn)
        if (base and 3 != 0) stop("unaligned multiple transfer address=${hex(base)}")
        val bytes = Integer.bitCount(list) * 4
        var addr = if (decrement) base - bytes else base
        var target: Int? = null
        for (i in 0..15) if (list and (1 shl i) != 0) {
            if (load) { val value = bus.read32(addr); if (i == 15) target = value else writeRegister(i, value) }
            else bus.write32(addr, readRegister(i))
            addr += 4
        }
        if (writeback) writeRegister(rn, if (decrement) base - bytes else base + bytes)
        target?.let { branch(it) }
    }

    private fun expanded(imm: Int, flags: Boolean): Int {
        val byte = imm and 255
        if (imm ushr 10 == 0) {
            if (imm ushr 8 != 0 && byte == 0) stop("unpredictable modified immediate")
            return when (imm ushr 8) {
                0 -> byte; 1 -> (byte shl 16) or byte; 2 -> (byte shl 24) or (byte shl 8)
                else -> byte * 0x01010101
            }
        }
        val value = Integer.rotateRight((imm and 127) or 128, imm ushr 7)
        if (flags) setCarry(value < 0)
        return value
    }

    private fun dataProcessing(kind: Int, rn: Int, rd: Int, operand: Int, flags: Boolean) {
        val a = if (rn == 15) 0 else readRegister(rn)
        val result = when (kind) {
            0 -> a and operand; 1 -> a and operand.inv(); 2 -> a or operand
            3 -> a or operand.inv(); 4 -> a xor operand
            8 -> add(a, operand, false, flags); 10 -> add(a, operand, c, flags)
            11 -> add(a, operand.inv(), c, flags); 13 -> add(a, operand.inv(), true, flags)
            14 -> add(operand, a.inv(), true, flags)
            else -> stop("unimplemented data processing operation $kind")
        }
        if (flags && kind < 8) setNZ(result)
        if (rd != 15) writeRegister(rd, result)
        else if (!flags) stop("unsupported data processing PC destination")
    }

    private fun specialRead(sysm: Int): Int = when (sysm) {
        0 -> apsr; 1 -> apsr or ipsr; 2 -> xpsr() and 0x0700fc00; 3 -> xpsr()
        5 -> ipsr; 6 -> xpsr() and 0x0700fc00; 7 -> xpsr() and 0x0700fdff
        8 -> msp; 9 -> psp; 16 -> primask; 17, 18 -> basepri; 19 -> faultmask; 20 -> control
        else -> stop("unimplemented MRS sysm=$sysm")
    }

    private fun specialWrite(sysm: Int, value: Int) {
        when (sysm) {
            0, 1, 2, 3 -> apsr = value and 0xf8000000.toInt()
            8 -> msp = value and -4; 9 -> psp = value and -4
            16 -> primask = value and 1; 17 -> basepri = value and 0xf0
            18 -> if (value and 0xf0 != 0 && (basepri == 0 || value and 0xf0 < basepri)) basepri = value and 0xf0
            19 -> faultmask = value and 1
            20 -> { if (value and 4 != 0) stop("FP context not implemented"); control = value and 3 }
            else -> stop("unimplemented MSR sysm=$sysm")
        }
    }

    private fun execute32(a: Int, b: Int) {
        val rn = a and 15; val rd = (b ushr 8) and 15; val rm = b and 15
        val imm12 = ((a ushr 10) and 1) * 2048 + ((b ushr 12) and 7) * 256 + (b and 255)
        when {
            a == 0xf3ef && b and 0xf000 == 0x8000 -> writeRegister(rd, specialRead(b and 255))
            a and 0xfff0 == 0xf380 && b and 0xff00 == 0x8800 -> specialWrite(b and 255, readRegister(rn))
            a == 0xf3bf && b == 0x8f2f -> exclusiveAddress = null
            a == 0xf3bf && b in intArrayOf(0x8f4f, 0x8f5f, 0x8f6f) -> Unit
            a == 0xf3af && b == 0x8000 -> Unit
            a and 0xf800 == 0xf000 && b and 0x8000 != 0 -> {
                val s = (a ushr 10) and 1; val j1 = (b ushr 13) and 1; val j2 = (b ushr 11) and 1
                val kind = b and 0xd000
                if (kind == 0x8000) {
                    val cond = (a ushr 6) and 15
                    if (cond >= 14) stop("unimplemented control instruction")
                    val offset = (s shl 20) or (j2 shl 19) or (j1 shl 18) or ((a and 63) shl 12) or ((b and 0x7ff) shl 1)
                    if (condition(cond)) pc = address + 4 + sign(offset, 21)
                } else if (kind == 0x9000 || kind == 0xd000) {
                    val offset = (s shl 24) or ((1 xor (j1 xor s)) shl 23) or ((1 xor (j2 xor s)) shl 22) or
                        ((a and 0x3ff) shl 12) or ((b and 0x7ff) shl 1)
                    if (kind == 0xd000) r[14] = pc or 1
                    pc = address + 4 + sign(offset, 25)
                } else stop("BLX immediate is not supported on M-profile")
            }
            a and 0xfa00 == 0xf000 && b and 0x8000 == 0 -> {
                val flags = a and 16 != 0
                val kind = (a ushr 5) and 15
                val logical = kind < 8
                dataProcessing(kind, rn, rd, expanded(imm12, flags && logical), flags)
            }
            a and 0xfbf0 == 0xf240 || a and 0xfbf0 == 0xf2c0 -> {
                val imm = ((a and 15) shl 12) or imm12
                r[rd] = if (a and 0x80 == 0) imm else (r[rd] and 65535) or (imm shl 16)
            }
            a and 0xfbf0 == 0xf200 || a and 0xfbf0 == 0xf2a0 -> {
                val base = if (rn == 15) (address + 4) and -4 else readRegister(rn)
                writeRegister(rd, base + if (a and 0x80 == 0) imm12 else -imm12)
            }
            a and 0xfbe0 == 0xf340 || a and 0xfbe0 == 0xf3c0 -> {
                val lsb = ((b ushr 12) and 7) * 4 + ((b ushr 6) and 3); val width = (b and 31) + 1
                if (lsb + width > 32) stop("invalid bitfield width")
                val field = readRegister(rn) ushr lsb
                r[rd] = if (a and 0x80 == 0) sign(field, width) else field and (-1 ushr (32 - width))
            }
            a and 0xfbf0 == 0xf360 -> {
                val lsb = ((b ushr 12) and 7) * 4 + ((b ushr 6) and 3); val width = (b and 31) - lsb + 1
                if (width <= 0) stop("invalid BFI width")
                val mask = (-1 ushr (32 - width)) shl lsb
                r[rd] = (r[rd] and mask.inv()) or ((if (rn == 15) 0 else r[rn] shl lsb) and mask)
            }
            a and 0xfe00 == 0xea00 && b and 0x8000 == 0 -> {
                val flags = a and 16 != 0; val kind = (a ushr 5) and 15
                val amount = ((b ushr 12) and 7) * 4 + ((b ushr 6) and 3)
                val value = shift(readRegister(rm), (b ushr 4) and 3, amount, flags && kind < 8, true)
                dataProcessing(kind, rn, rd, value, flags)
            }
            a and 0xfe40 == 0xe800 && a and 0x180 != 0 ->
                multiple(rn, b, a and 16 != 0, a and 0x100 != 0, a and 32 != 0)
            a and 0xfff0 == 0xe850 && b and 0xf00 == 0xf00 -> {
                val addr = readRegister(rn) + (b and 255) * 4
                if (addr and 3 != 0) stop("unaligned LDREX")
                r[b ushr 12] = bus.read32(addr); exclusiveAddress = addr
            }
            a and 0xfff0 == 0xe840 -> {
                val addr = readRegister(rn) + (b and 255) * 4
                if (addr and 3 != 0) stop("unaligned STREX")
                val ok = exclusiveAddress == addr
                if (ok) bus.write32(addr, r[b ushr 12])
                r[rd] = if (ok) 0 else 1; exclusiveAddress = null
            }
            a and 0xfff0 == 0xe8d0 && b and 0xffe0 == 0xf000 -> {
                val index = readRegister(rm) * if (b and 16 == 0) 1 else 2
                val value = bus.read(readRegister(rn) + index, if (b and 16 == 0) 1 else 2)
                pc = address + 4 + value * 2
            }
            a and 0xfe40 == 0xe840 -> {
                val pre = a and 0x100 != 0; val up = a and 0x80 != 0; val wb = a and 32 != 0
                if (!pre && !wb) stop("unimplemented dual/exclusive instruction")
                val base = if (rn == 15) (address + 4) and -4 else readRegister(rn)
                val updated = base + (b and 255) * if (up) 4 else -4
                val addr = if (pre) updated else base
                if (addr and 3 != 0) stop("unaligned dual transfer")
                val rt = b ushr 12; val rt2 = rd
                if (a and 16 != 0) { r[rt] = bus.read32(addr); r[rt2] = bus.read32(addr + 4) }
                else { bus.write32(addr, r[rt]); bus.write32(addr + 4, r[rt2]) }
                if (wb) writeRegister(rn, updated)
            }
            a and 0xfe00 == 0xf800 -> singleTransfer(a, b)
            a and 0xff80 == 0xfa00 && b and 0xf0f0 == 0xf000 -> {
                val flags = a and 16 != 0
                val result = shift(readRegister(rn), (a ushr 5) and 3, readRegister(rm) and 255, flags)
                r[rd] = result; if (flags) setNZ(result)
            }
            a and 0xff80 == 0xfa00 && b and 0xf0c0 == 0xf080 -> {
                // A5.3.12 / A7.7: extend byte/halfword, optionally add and rotate.
                val kind = (a ushr 4) and 7
                val rotated = Integer.rotateRight(readRegister(rm), ((b ushr 4) and 3) * 8)
                val extended = when (kind) {
                    0 -> sign(rotated, 16); 1 -> rotated and 65535
                    4 -> sign(rotated, 8); 5 -> rotated and 255
                    else -> stop("unimplemented packed extension")
                }
                r[rd] = extended + if (rn == 15) 0 else readRegister(rn)
            }
            a and 0xfff0 == 0xfab0 && b and 0xf0f0 == 0xf080 -> r[rd] = Integer.numberOfLeadingZeros(r[rm])
            a and 0xfff0 == 0xfa90 && b and 0xf0c0 == 0xf080 -> r[rd] = when ((b ushr 4) and 3) {
                0 -> Integer.reverseBytes(r[rm]); 1 -> ((r[rm] and 0x00ff00ff) shl 8) or ((r[rm] ushr 8) and 0x00ff00ff)
                2 -> Integer.reverse(r[rm]); else -> sign(Integer.reverseBytes(r[rm]) ushr 16, 16)
            }
            a and 0xfff0 == 0xfb00 && b and 0xe0 == 0 -> {
                val ra = b ushr 12
                val product = readRegister(rn) * readRegister(rm)
                r[rd] = if (ra == 15) product else if (b and 16 != 0) r[ra] - product else r[ra] + product
            }
            (a and 0xfff0 == 0xfb90 || a and 0xfff0 == 0xfbb0) && b and 0xf0f0 == 0xf0f0 -> {
                val dividend = r[rn]; val divisor = r[rm]
                if (divisor == 0 && system.ccr and 16 != 0) stop("division by zero trap")
                r[rd] = if (divisor == 0) 0 else if (a and 32 == 0) dividend / divisor
                    else ((dividend.toLong() and 0xffffffffL) / (divisor.toLong() and 0xffffffffL)).toInt()
            }
            a and 0xff90 == 0xfb80 && b and 0xf0 == 0 -> {
                val unsigned = a and 32 != 0; val accumulate = a and 64 != 0
                var value = if (unsigned) (r[rn].toLong() and 0xffffffffL) * (r[rm].toLong() and 0xffffffffL)
                    else r[rn].toLong() * r[rm].toLong()
                val lo = b ushr 12; val hi = rd
                if (accumulate) value += (r[lo].toLong() and 0xffffffffL) or (r[hi].toLong() shl 32)
                r[lo] = value.toInt(); r[hi] = (value ushr 32).toInt()
            }
            else -> stop("unimplemented instruction")
        }
    }

    private fun singleTransfer(a: Int, b: Int) {
        val rn = a and 15; val rt = b ushr 12
        val kind = (a ushr 4) and 7
        val load = kind and 1 != 0
        val size = when (kind ushr 1) { 0 -> 1; 1 -> 2; 2 -> 4; else -> stop("unimplemented load/store size") }
        val signed = a and 0x100 != 0
        if (signed && !load) stop("unimplemented store encoding")
        if (rt == 15 && size != 4) stop("unimplemented memory hint")
        val base = if (rn == 15) (address + 4) and -4 else readRegister(rn)
        var addr: Int
        var updated = base
        var writeback = false
        if (rn == 15) addr = base + (b and 0xfff) * if (a and 0x80 != 0) 1 else -1
        else if (a and 0x80 != 0) addr = base + (b and 0xfff)
        else if (b and 0x800 == 0) {
            if (b and 0xfc0 != 0) stop("invalid register offset load/store")
            addr = base + (readRegister(b and 15) shl ((b ushr 4) and 3))
        } else {
            val pre = b and 0x400 != 0
            writeback = b and 0x100 != 0
            if (!pre && !writeback) stop("unimplemented unprivileged transfer")
            updated = base + (b and 255) * if (b and 0x200 != 0) 1 else -1
            addr = if (pre) updated else base
        }
        if (system.ccr and 8 != 0 && addr and (size - 1) != 0) stop("unaligned transfer")
        if (load) {
            var value = bus.read(addr, size)
            if (signed) value = sign(value, size * 8)
            if (writeback) writeRegister(rn, updated)
            writeRegister(rt, value)
        } else {
            bus.write(addr, size, readRegister(rt))
            if (writeback) writeRegister(rn, updated)
        }
    }
}
