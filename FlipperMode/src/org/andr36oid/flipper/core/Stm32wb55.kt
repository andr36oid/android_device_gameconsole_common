package org.andr36oid.flipper.core

// DS11929 section 5 and RM0434 Rev 4 section 2.2.2: CPU1 memory map. SRAM2a/b are
// each 32 KiB; the 0x10000000 alias exposes the same bytes, not another bank.
class Stm32wb55(firmware: ByteArray) {
    val bus = MemoryBus()
    val system = SystemControl()
    val cpu = CortexM4(bus, system)
    val sram1 = Ram(0x30000)
    val sram2 = Ram(0x10000)

    init {
        require(firmware.size in 8..0x100000)
        val bytes = ByteArray(0x100000) { 0xff.toByte() }
        firmware.copyInto(bytes)
        val flash = Ram(bytes, true)
        bus.map(0, 0x100000, "boot flash alias", flash)
        bus.map(0x08000000, 0x100000, "flash", flash)
        bus.map(0x20000000, 0x30000, "SRAM1", sram1)
        bus.map(0x20030000, 0x10000, "SRAM2", sram2)
        bus.map(0x10000000, 0x10000, "SRAM2 alias", sram2)
        bus.map(0xe000e000.toInt(), 0x1000, "SCS", system)
        bus.map(0xe0001000.toInt(), 0x1000, "DWT", Dwt(system))
        bus.map(0x58000000, 0x400, "RCC", Rcc())
        bus.map(0x58004000, 0x400, "FLASH", FlashControl())
        bus.map(0x58000400, 0x400, "PWR", PowerControl())
        bus.map(0x58000800, 0x400, "EXTI", Exti())
        bus.map(0x40010000, 0x400, "SYSCFG", SystemConfig())
        for (port in intArrayOf(0, 1, 2, 3, 4, 7))
            bus.map(0x48000000 + port * 0x400, 0x400, "GPIO$port", Gpio(port))
        bus.map(0x40005400, 0x400, "I2C1 (no slaves)", EmptyI2c())
        bus.map(0x40002800, 0x400, "RTC", RtcBackup())
        bus.map(0x40013800, 0x400, "USART1", BootUsart())
        bus.map(0xe0042000.toInt(), 0x400, "DBGMCU", DebugControl())
        bus.map(0x1fff7000, 0x400, "erased OTP", Ram(ByteArray(0x400) { 0xff.toByte() }, true))
        // RM0434 Rev 4 section 3.5: deterministic virtual UID64, not a real chip ID.
        val identity = Ram(byteArrayOf(1, 0, 0, 0, 0x26, 0, 0x80.toByte(), 0))
        bus.map(0x1fff7580, 8, "virtual UID64", identity)
        cpu.reset()
    }
}

// ARM DDI 0403D C1.8: DWT_CTRL and DWT_CYCCNT; one virtual cycle per step.
class Dwt(private val clock: SystemControl) : MemoryDevice {
    private var control = 0
    private var start = 0L
    private var saved = 0
    override fun read(offset: Int, size: Int): Int {
        if (size != 4) throw EmulationStop("DWT requires word access")
        return when (offset) {
            0 -> control
            4 -> saved + if (control and 1 != 0 && clock.demcr and (1 shl 24) != 0) (clock.cycles - start).toInt() else 0
            else -> throw EmulationStop("unimplemented DWT read offset=${hex(offset)}")
        }
    }
    override fun write(offset: Int, size: Int, value: Int) {
        if (size != 4) throw EmulationStop("DWT requires word access")
        when (offset) {
            0 -> { saved = read(4, 4); start = clock.cycles; control = value }
            4 -> { saved = value; start = clock.cycles }
            else -> throw EmulationStop("unimplemented DWT write offset=${hex(offset)} value=${hex(value)}")
        }
    }
}

// RM0434 Rev 4 section 8.4, Reset and clock control (RCC), RCC_CR,
// RCC_CFGR, RCC_PLLCFGR, RCC_PLLSAI1CFGR, RCC_CIER, RCC_CSR and RCC_CRRCR.
// Oscillator readiness settles immediately; PLL lock timing is outside M1.
class Rcc : MemoryDevice {
    private val registers = mutableMapOf(0 to 0x63, 8 to 0, 0xc to 0x22041000,
        0x10 to 0x22041000, 0x18 to 0, 0x90 to 0, 0x94 to 0x0c000600, 0x98 to 0)

    init {
        // RM0434 RCC register map: explicit reset/enable/sleep/clock selectors.
        for (offset in intArrayOf(0x1c, 0x20, 0x24, 0x28, 0x2c, 0x30, 0x38, 0x3c, 0x40,
            0x48, 0x4c, 0x50, 0x58, 0x5c, 0x60, 0x68, 0x6c, 0x70, 0x78, 0x7c, 0x80,
            0x44, 0x88, 0x9c, 0x108, 0x148, 0x14c, 0x150, 0x158, 0x15c, 0x160,
            0x164, 0x168, 0x16c, 0x170, 0x178, 0x17c, 0x180, 0x184)) registers[offset] = 0
    }

    override fun read(offset: Int, size: Int): Int {
        if (size != 4 || offset and 3 != 0) throw EmulationStop("RCC requires aligned word access")
        return registers[offset] ?: throw EmulationStop("unimplemented RCC read offset=${hex(offset)}")
    }

    override fun write(offset: Int, size: Int, value: Int) {
        read(offset, size)
        registers[offset] = when (offset) {
            0 -> {
                var result = value
                for ((on, ready) in listOf(0 to 1, 8 to 10, 16 to 17, 24 to 25, 26 to 27))
                    result = (result and (1 shl ready).inv()) or (if (value and (1 shl on) != 0) 1 shl ready else 0)
                result
            }
            8 -> (value and 12.inv()) or ((value and 3) shl 2)
            0x90 -> (value and 2.inv()) or (if (value and 1 != 0) 2 else 0)
            0x94 -> (value and 10.inv()) or (if (value and 1 != 0) 2 else 0) or (if (value and 4 != 0) 8 else 0)
            0x98 -> (value and 2.inv()) or (if (value and 1 != 0) 2 else 0)
            0x20 -> { registers[0x1c] = registers[0x1c]!! and value.inv(); 0 }
            0x1c -> throw EmulationStop("write to read-only RCC_CIFR")
            else -> value
        }
    }
}

// RM0434 Rev 4 sections 3.10.1/3.10.4, FLASH_ACR and FLASH_SR (W1C).
// Latency/cache configuration and cleared error flags suffice for M1;
// programming and erase deliberately stop. Unknown reset SR bits start zero.
class FlashControl : MemoryDevice {
    private var acr = 0x600
    private var status = 0
    override fun read(offset: Int, size: Int): Int {
        if (offset == 0 && size == 4) return acr
        if (offset == 0x10 && size == 4) return status
        throw EmulationStop("unimplemented FLASH read offset=${hex(offset)} size=$size")
    }
    override fun write(offset: Int, size: Int, value: Int) {
        if (offset == 0 && size == 4) acr = value
        else if (offset == 0x10 && size == 4) status = status and value.inv()
        else throw EmulationStop("unimplemented FLASH write offset=${hex(offset)} value=${hex(value)} size=$size")
    }
}
