package org.andr36oid.flipper.core

// ARM DDI 0403D B3.2 (SCB), B3.3 (SysTick), B3.4 (NVIC).
// STM32WB55 implements four priority bits and 63 external interrupts (RM0434, NVIC).
class SystemControl : MemoryDevice {
    var vtor = 0
    var ccr = 0x200
    var aircr = 0
    var cpacr = 0
    private var shcsr = 0
    var demcr = 0
    var fpccr = 0xc0000000.toInt()
    var systickControl = 0
        private set
    var systickReload = 0
        private set
    var systickValue = 0
        private set
    var cycles = 0L
        private set
    var systickWraps = 0L
        private set
    private var countFlag = false
    private var divider = 0
    private val enabled = BooleanArray(79)
    private val pending = BooleanArray(79)
    private val active = BooleanArray(79)
    private val activeOrder = ArrayList<Int>()
    private val priority = IntArray(79)
    private var mpuControl = 0
    private var mpuRegion = 0
    private val mpuBase = IntArray(8)
    private val mpuAttributes = IntArray(8)

    fun reset() {
        vtor = 0; ccr = 0x200; aircr = 0; cpacr = 0; demcr = 0; fpccr = 0xc0000000.toInt()
        shcsr = 0
        systickControl = 0; systickReload = 0; systickValue = 0; cycles = 0; systickWraps = 0
        countFlag = false; divider = 0
        enabled.fill(false); pending.fill(false); active.fill(false); priority.fill(0)
        mpuControl = 0; mpuRegion = 0; mpuBase.fill(0); mpuAttributes.fill(0)
        activeOrder.clear()
    }

    // ARM DDI 0403D B3.5: eight unified MPU regions, priority by region number,
    // subregion disable, AP permissions, XN and the privileged background map.
    // Attribute timing and memory ordering are outside the instruction-time model.
    fun checkMpu(address: Int, size: Int, write: Boolean, execute: Boolean, privileged: Boolean, exception: Int) {
        if (address ushr 20 == 0xe00) {
            if (!privileged || execute) throw EmulationStop("invalid PPB access address=${hex(address)}")
            return
        }
        if (mpuControl and 1 == 0 || (exception in 2..3 && mpuControl and 2 == 0)) return
        if (address and (size - 1) != 0) {
            for (i in 0 until size) checkMpu(address + i, 1, write, execute, privileged, exception)
            return
        }
        val start = address.toLong() and 0xffffffffL
        for (i in 7 downTo 0) {
            val attr = mpuAttributes[i]
            if (attr and 1 == 0) continue
            val bytes = 1L shl (((attr ushr 1) and 31) + 1)
            val base = (mpuBase[i].toLong() and 0xffffffffL) and (bytes - 1).inv()
            if (start < base || start + size > base + bytes) continue
            if (bytes >= 256 && attr and (1 shl (8 + ((start - base) / (bytes / 8)).toInt())) != 0) continue
            val ap = (attr ushr 24) and 7
            val readable = when (ap) { 1, 5 -> privileged; 2, 3, 6, 7 -> true; else -> false }
            val writable = when (ap) { 1, 2 -> privileged; 3 -> true; else -> false }
            if (!readable || (write && !writable) || (execute && attr and (1 shl 28) != 0))
                throw EmulationStop("MPU access denied region=$i address=${hex(address)} size=$size write=$write execute=$execute")
            return
        }
        if (!(privileged && mpuControl and 4 != 0))
            throw EmulationStop("MPU background access denied address=${hex(address)}")
    }

    fun pend(number: Int) {
        require(number in 2..78)
        pending[number] = true
    }

    fun priorityOf(number: Int) = effectivePriority(number)
    fun acknowledge(number: Int) { pending[number] = false; active[number] = true; activeOrder.add(number) }
    fun complete(number: Int) { active[number] = false; activeOrder.remove(number) }

    private fun effectivePriority(number: Int): Int = when (number) {
        0 -> 256; 2 -> -2; 3 -> -1; else -> priority[number]
    }

    private fun groupPriority(number: Int): Int {
        val p = effectivePriority(number)
        if (p < 0 || number == 0) return p
        val subBits = maxOf(0, ((aircr ushr 8) and 7) - 3)
        return p ushr (4 + subBits)
    }

    fun nextException(current: Int, primask: Int, basepri: Int, faultmask: Int): Int {
        var best = 0
        for (number in 2..78) {
            if (!pending[number] || active[number] || (number >= 16 && !enabled[number])) continue
            if (faultmask != 0 && number != 2) continue
            if (number > 3 && (primask != 0 || (basepri != 0 && groupPriority(number) >=
                    (basepri ushr (4 + maxOf(0, ((aircr ushr 8) and 7) - 3)))))) continue
            if (groupPriority(number) >= groupPriority(current)) continue
            if (best == 0 || effectivePriority(number) < effectivePriority(best)) best = number
        }
        return best
    }

    // Deterministic instruction-time clock. This is not a cycle-accurate pipeline model.
    fun tick() {
        cycles++
        if (systickControl and 1 == 0) return
        if (systickControl and 4 == 0) { divider = (divider + 1) and 7; if (divider != 0) return }
        if (systickValue == 0) systickValue = systickReload
        else {
            systickValue--
            if (systickValue == 0) {
                countFlag = true; systickWraps++
                if (systickControl and 2 != 0) pend(15)
            }
        }
    }

    private fun bits(array: BooleanArray, bank: Int): Int {
        var result = 0
        for (i in 0..31) {
            val number = 16 + bank * 32 + i
            if (number < array.size && array[number]) result = result or (1 shl i)
        }
        return result
    }

    private fun bankWrite(array: BooleanArray, bank: Int, value: Int, set: Boolean) {
        for (i in 0..31) if (value and (1 shl i) != 0) {
            val number = 16 + bank * 32 + i
            if (number >= array.size) throw EmulationStop("NVIC nonexistent IRQ ${number - 16}")
            array[number] = set
        }
    }

    private fun priorityIndex(offset: Int): Int = when (offset) {
        in 0x400..0x43e -> 16 + offset - 0x400
        in 0xd18..0xd23 -> 4 + offset - 0xd18
        else -> -1
    }

    override fun read(offset: Int, size: Int): Int {
        val pi = priorityIndex(offset)
        if (pi >= 0) {
            var result = 0
            for (i in 0 until size) {
                val index = priorityIndex(offset + i)
                if (index < 0) throw EmulationStop("SCB/NVIC priority boundary")
                result = result or (priority[index] shl (i * 8))
            }
            return result
        }
        if (size != 4 || offset and 3 != 0) throw EmulationStop("SCS requires word access offset=${hex(offset)} size=$size")
        return when (offset) {
            0x10 -> { val value = systickControl or if (countFlag) 0x10000 else 0; countFlag = false; value }
            0x14 -> systickReload; 0x18 -> systickValue
            0x1c -> 0x80000000.toInt() // External reference clock calibration is unavailable.
            0x100, 0x104, 0x180, 0x184 -> bits(enabled, (offset and 127) / 4)
            0x200, 0x204, 0x280, 0x284 -> bits(pending, (offset and 127) / 4)
            0x300, 0x304 -> bits(active, (offset - 0x300) / 4)
            0xd00 -> 0x410fc241 // Cortex-M4 r0p1 CPUID, ARM DDI 0439.
            0xd04 -> {
                val current = activeOrder.lastOrNull() ?: 0
                val next = nextException(0, 0, 0, 0)
                current or (next shl 12) or (if (activeOrder.size <= 1) 1 shl 11 else 0) or
                    (if ((16..78).any { pending[it] && enabled[it] }) 1 shl 22 else 0) or (if (pending[14]) 1 shl 28 else 0) or
                    (if (pending[15]) 1 shl 26 else 0) or (if (pending[2]) Int.MIN_VALUE else 0)
            }
            0xd08 -> vtor; 0xd0c -> 0xfa050000.toInt() or aircr
            0xd10 -> 0; 0xd14 -> ccr; 0xd24 -> shcsr
            0xd28, 0xd2c, 0xd30, 0xd34, 0xd38 -> 0 // Guest faults are host stops in M1.
            0xd88 -> cpacr; 0xdfc -> demcr; 0xf34 -> fpccr
            0xd90 -> 0x800
            0xd94 -> mpuControl; 0xd98 -> mpuRegion
            0xd9c -> mpuBase[mpuRegion]; 0xda0 -> mpuAttributes[mpuRegion]
            else -> throw EmulationStop("unimplemented SCB/NVIC/SysTick register offset=${hex(offset)} read size=$size")
        }
    }

    override fun write(offset: Int, size: Int, value: Int) {
        val pi = priorityIndex(offset)
        if (pi >= 0) {
            for (i in 0 until size) {
                val index = priorityIndex(offset + i)
                if (index < 0) throw EmulationStop("SCB/NVIC priority boundary")
                priority[index] = (value ushr (i * 8)) and 0xf0
            }
            return
        }
        if (size != 4 || offset and 3 != 0) throw EmulationStop("SCS requires word access offset=${hex(offset)} size=$size")
        when (offset) {
            0x10 -> systickControl = value and 7
            0x14 -> systickReload = value and 0xffffff
            0x18 -> { systickValue = 0; countFlag = false; divider = 0 }
            0x100, 0x104 -> bankWrite(enabled, (offset - 0x100) / 4, value, true)
            0x180, 0x184 -> bankWrite(enabled, (offset - 0x180) / 4, value, false)
            0x200, 0x204 -> bankWrite(pending, (offset - 0x200) / 4, value, true)
            0x280, 0x284 -> bankWrite(pending, (offset - 0x280) / 4, value, false)
            0xd04 -> {
                if (value < 0) pend(2)
                if (value and (1 shl 28) != 0) pend(14)
                if (value and (1 shl 27) != 0) pending[14] = false
                if (value and (1 shl 26) != 0) pend(15)
                if (value and (1 shl 25) != 0) pending[15] = false
            }
            0xd08 -> vtor = value and -512
            0xd0c -> if (value ushr 16 == 0x05fa) {
                if (value and 7 != 0) throw EmulationStop("SCB AIRCR reset request=${hex(value)}")
                aircr = value and 0x700
            }
            0xd14 -> {
                if (value and 0x200.inv() and 0x18.inv() != 0) throw EmulationStop("unsupported SCB CCR=${hex(value)}")
                ccr = value
            }
            0xd88 -> cpacr = value and 0x00f00000
            0xd24 -> {
                if (value and 0x70000.inv() != 0) throw EmulationStop("unsupported SHCSR pending/active write=${hex(value)}")
                shcsr = value
            }
            0xdfc -> demcr = value
            0xf34 -> fpccr = value
            0xd94 -> {
                if (value and 7.inv() != 0) throw EmulationStop("invalid MPU_CTRL=${hex(value)}")
                mpuControl = value
            }
            0xd98 -> { if (value !in 0..7) throw EmulationStop("invalid MPU region=$value"); mpuRegion = value }
            0xd9c -> {
                if (value and 16 != 0) {
                    val selected = value and 15
                    if (selected > 7) throw EmulationStop("invalid MPU RBAR region=$selected")
                    mpuRegion = selected
                }
                mpuBase[mpuRegion] = value and -32
            }
            0xda0 -> {
                if (value and 1 != 0 && ((value ushr 1) and 31) < 4) throw EmulationStop("MPU region smaller than 32 bytes")
                if ((value ushr 24) and 7 == 4) throw EmulationStop("reserved MPU access permission")
                mpuAttributes[mpuRegion] = value
            }
            0xf00 -> { val irq = value and 0x1ff; if (irq >= 63) throw EmulationStop("invalid STIR IRQ=$irq"); pend(irq + 16) }
            else -> throw EmulationStop("unimplemented SCB/NVIC/SysTick register offset=${hex(offset)} write=${hex(value)} size=$size")
        }
    }
}
