package org.andr36oid.flipper.core

// These blocks implement the configuration used before the first scheduler
// tick. There is no permissive fallback for registers outside the listed set.
abstract class WordRegisters(private val name: String, initial: Map<Int, Int>) : MemoryDevice {
    protected val values = initial.toMutableMap()
    protected fun checkAccess(offset: Int, size: Int) {
        if (size != 4 || offset !in values) throw EmulationStop("unimplemented $name register offset=${hex(offset)} size=$size")
    }
    override fun read(offset: Int, size: Int): Int { checkAccess(offset, size); return values[offset]!! }
    override fun write(offset: Int, size: Int, value: Int) { checkAccess(offset, size); values[offset] = value }
}

// RM0434 Rev 4 section 6.6: PWR_CR1 backup-domain write permission (DBP)
// and PUCRx/PDCRx GPIO retention pull configuration.
// M1 does not implement regulator changes, stop modes or CPU2 power control.
class PowerControl : WordRegisters("PWR", mapOf(0 to 0x200, 8 to 0x8000) +
    intArrayOf(0x20, 0x24, 0x28, 0x2c, 0x30, 0x34, 0x38, 0x3c, 0x40, 0x44, 0x58, 0x5c).associate { it to 0 }) {
    override fun write(offset: Int, size: Int, value: Int) {
        checkAccess(offset, size)
        if (offset == 0 && value and 0x100.inv() != values[offset]!! and 0x100.inv())
            throw EmulationStop("unimplemented PWR_CR1 mode change value=${hex(value)}")
        if (offset == 8 && value and 0x400.inv() != values[offset]!! and 0x400.inv())
            throw EmulationStop("unimplemented PWR_CR3 mode change value=${hex(value)}")
        values[offset] = value
    }
}

// RM0434 Rev 4 section 9.4: MODER, OTYPER, OSPEEDR, PUPDR, IDR, ODR,
// BSRR, AFRL/H and BRR. External pins are disconnected; pulls and output
// latches determine IDR. Buttons therefore read released after pull-up setup.
class Gpio(port: Int) : WordRegisters("GPIO", mapOf(0 to (if (port == 0) 0xabffffff.toInt() else if (port == 1) 0xfffffebf.toInt() else -1),
    4 to 0, 8 to (if (port == 0) 0x0c000000 else if (port == 1) 0xc0 else 0),
    0xc to (if (port == 0) 0x64000000 else if (port == 1) 0x100 else 0),
    0x10 to 0, 0x14 to 0, 0x18 to 0, 0x20 to 0, 0x24 to 0, 0x28 to 0)) {
    override fun read(offset: Int, size: Int): Int {
        checkAccess(offset, size)
        if (offset == 0x18 || offset == 0x28) return 0
        if (offset != 0x10) return values[offset]!!
        var result = 0
        for (pin in 0..15) {
            val mode = (values[0]!! ushr (pin * 2)) and 3
            val pull = (values[0xc]!! ushr (pin * 2)) and 3
            val high = if (mode == 1) values[0x14]!! and (1 shl pin) != 0 else pull == 1
            if (high) result = result or (1 shl pin)
        }
        return result
    }
    override fun write(offset: Int, size: Int, value: Int) {
        checkAccess(offset, size)
        when (offset) {
            0x10 -> throw EmulationStop("write to GPIO IDR")
            0x18 -> values[0x14] = ((values[0x14]!! and (value ushr 16).inv()) or value) and 65535
            0x28 -> values[0x14] = values[0x14]!! and value.inv() and 65535
            else -> values[offset] = value
        }
    }
}

// RM0434 Rev 4 section 10.2: SYSCFG_EXTICR1..4 route EXTI inputs;
// CFGR1 configures FPU exception IRQ gates. FP instructions stop in M1.
// No external transitions are generated in M1.
class SystemConfig : WordRegisters("SYSCFG", mapOf(4 to 0x7c000001, 8 to 0, 0xc to 0, 0x10 to 0, 0x14 to 0))

// RM0434 Rev 4 section 14.5: RTSR/FTSR and CPU1 IMR/EMR, PR1/PR2 W1C.
// Software interrupt generation and CPU2 event delivery are not implemented.
class Exti : WordRegisters("EXTI", mapOf(0 to 0, 4 to 0, 0xc to 0,
    0x20 to 0, 0x24 to 0, 0x2c to 0, 0x80 to 0, 0x84 to 0, 0x90 to 0, 0x94 to 0)) {
    override fun write(offset: Int, size: Int, value: Int) {
        checkAccess(offset, size)
        values[offset] = if (offset == 0xc || offset == 0x2c) values[offset]!! and value.inv() else value
    }
}

// RM0434 Rev 4 section 32.7: I2C_CR1/CR2, TIMINGR, ISR/ICR, RXDR/TXDR.
// The bus has no slave devices in M1. START receives NACKF and STOPF, as on
// a disconnected bus; this is an explicit absence, not an ACK/data stub.
class EmptyI2c : WordRegisters("I2C", mapOf(0 to 0, 4 to 0, 8 to 0, 0xc to 0, 0x10 to 0, 0x14 to 0,
    0x18 to 1, 0x1c to 0, 0x20 to 0, 0x24 to 0, 0x28 to 0)) {
    override fun write(offset: Int, size: Int, value: Int) {
        checkAccess(offset, size)
        when (offset) {
            0x18 -> throw EmulationStop("write to I2C_ISR")
            0x1c -> values[0x18] = values[0x18]!! and (value and 0x3f38).inv()
            4 -> {
                if (value and 0x2000 != 0) {
                    if (values[0]!! and 1 == 0) throw EmulationStop("I2C START while disabled")
                    values[0x18] = values[0x18]!! or 0x30
                }
                values[4] = value and 0x6000.inv()
            }
            0 -> { values[0] = value; if (value and 1 == 0) values[0x18] = 1 }
            0x24, 0x28 -> throw EmulationStop("I2C data transfer without a slave")
            else -> values[offset] = value
        }
    }
    override fun read(offset: Int, size: Int): Int {
        if (offset == 0x24) throw EmulationStop("I2C RXDR without a slave")
        return super.read(offset, size)
    }
}

// RM0434 Rev 4 sections 29.6.1..5, 29.6.9, 29.6.19/20: RTC initialization,
// shadow synchronization, WPR key sequence and twenty backup words. The
// calendar stays at its reset value during M1's subsecond boot interval.
// Oscillator synchronization completes on the following register read.
class RtcBackup : WordRegisters("RTC", (0 until 20).associate { 0x50 + it * 4 to 0 } +
    mapOf(0 to 0, 4 to 0x2101, 8 to 0, 0xc to 7, 0x10 to 0x007f00ff, 0x24 to 255, 0x4c to 0)) {
    private var key = 0
    private var unlocked = false
    private var syncPending = false
    override fun read(offset: Int, size: Int): Int {
        checkAccess(offset, size)
        val value = values[offset]!!
        if (offset == 0xc && syncPending) { values[0xc] = value or 0x20; syncPending = false }
        return value
    }
    override fun write(offset: Int, size: Int, value: Int) {
        checkAccess(offset, size)
        if (offset >= 0x50) { values[offset] = value; return }
        if (offset == 0x24) {
            if (value == 0x53 && key == 0xca) unlocked = true else { unlocked = false; key = value and 255 }
            return
        }
        if (!unlocked) return // Writes to protected RTC registers are ignored by hardware.
        when (offset) {
            0xc -> {
                val init = value and 0x80 != 0
                values[offset] = (values[offset]!! and 0xff.inv()) or 7 or (value and 0x20) or
                    (if (init) 0xc0 else 0x10)
                if (value and 0x20 == 0) syncPending = true
            }
            8 -> {
                if (value and 0x4300 != 0) throw EmulationStop("RTC alarm/wakeup generation is not implemented")
                values[offset] = value
            }
            0, 4, 0x10 -> {
                if (values[0xc]!! and 0x40 == 0) throw EmulationStop("RTC calendar write outside init mode")
                values[offset] = value
            }
            0x4c -> values[offset] = value
        }
    }
}

// RM0434 Rev 4 section 41.8: DBGMCU_CR. Debug port configuration readback;
// no debugger is attached and hardware breakpoints are not implemented.
class DebugControl : WordRegisters("DBGMCU", mapOf(4 to 0))

// RM0434 Rev 4 section 33.7: USART CR1/2/3, BRR, GTPR, RTOR, ISR/ICR,
// RDR/TDR and PRESC. M1 captures synchronous TX and has no RX source. TX completes
// immediately; baud timing, FIFO/DMA requests and TX IRQs are later work.
class BootUsart : WordRegisters("USART", mapOf(0 to 0, 4 to 0, 8 to 0, 0xc to 0, 0x10 to 0, 0x14 to 0,
    0x18 to 0, 0x1c to 0xc0, 0x20 to 0, 0x24 to 0, 0x28 to 0, 0x2c to 0)) {
    val output = ArrayList<Int>()
    override fun read(offset: Int, size: Int): Int {
        if (offset == 0x24) throw EmulationStop("USART RDR read with no received data")
        if (offset == 0x1c && size == 4) {
            val cr1 = values[0]!!
            return 0xc0 or (if (cr1 and 9 == 9) 1 shl 21 else 0) or (if (cr1 and 5 == 5) 1 shl 22 else 0)
        }
        return super.read(offset, size)
    }
    override fun write(offset: Int, size: Int, value: Int) {
        if (offset == 0x28 && size in intArrayOf(1, 2, 4)) {
            if (values[0]!! and 9 != 9) throw EmulationStop("USART transmit while disabled")
            output.add(value and 255)
            return
        }
        checkAccess(offset, size)
        when (offset) {
            0x1c, 0x24 -> throw EmulationStop("write to read-only USART register=${hex(offset)}")
            0 -> {
                if (value and 0xc0 != 0) throw EmulationStop("USART TX interrupts are not implemented")
                values[offset] = value
            }
            0x20 -> Unit // No received error flags in a disconnected receive path.
            0x2c -> {
                if (value and 15 != 0) throw EmulationStop("USART clock prescaling is not implemented")
                values[offset] = value
            }
            else -> values[offset] = value
        }
    }
}
