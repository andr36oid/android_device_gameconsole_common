package org.andr36oid.flipper.core

fun hex(value: Int): String = "0x" + java.lang.Integer.toHexString(value).padStart(8, '0')

class EmulationStop(message: String) : RuntimeException(message)

interface MemoryDevice {
    fun read(offset: Int, size: Int): Int
    fun write(offset: Int, size: Int, value: Int)
}

class Ram(val bytes: ByteArray, private val readOnly: Boolean = false) : MemoryDevice {
    constructor(size: Int) : this(ByteArray(size))

    override fun read(offset: Int, size: Int): Int {
        var value = 0
        for (i in 0 until size) value = value or ((bytes[offset + i].toInt() and 255) shl (i * 8))
        return value
    }

    override fun write(offset: Int, size: Int, value: Int) {
        if (readOnly) throw EmulationStop("write to read-only memory offset=${hex(offset)} size=$size")
        for (i in 0 until size) bytes[offset + i] = (value ushr (i * 8)).toByte()
    }
}

class MemoryBus {
    private data class Region(val base: Long, val size: Int, val name: String, val device: MemoryDevice)
    private val regions = ArrayList<Region>()
    var observer: ((String, Int, Int, Int) -> Unit)? = null
    var accessGuard: ((Int, Int, Boolean) -> Unit)? = null

    fun map(base: Int, size: Int, name: String, device: MemoryDevice) {
        val start = base.toLong() and 0xffffffffL
        require(size > 0 && start + size <= 0x100000000L)
        require(regions.none { start < it.base + it.size && it.base < start + size })
        regions.add(Region(start, size, name, device))
    }

    private fun region(address: Int, size: Int): Region {
        require(size == 1 || size == 2 || size == 4)
        val start = address.toLong() and 0xffffffffL
        return regions.firstOrNull { start >= it.base && start + size <= it.base + it.size }
            ?: throw EmulationStop("unmapped memory address=${hex(address)} size=$size")
    }

    fun read(address: Int, size: Int): Int = readAccess(address, size, true)

    // ARM DDI 0403D B3.5.1: hardware vector lookups bypass MPU permission checks.
    internal fun readVector32(address: Int): Int = readAccess(address, 4, false)

    private fun readAccess(address: Int, size: Int, checkProtection: Boolean): Int {
        val r = region(address, size)
        if (checkProtection) accessGuard?.invoke(address, size, false)
        val value = r.device.read(((address.toLong() and 0xffffffffL) - r.base).toInt(), size)
        observer?.invoke("read ${r.name}", address, size, value)
        return value
    }

    fun write(address: Int, size: Int, value: Int) {
        val r = region(address, size)
        accessGuard?.invoke(address, size, true)
        observer?.invoke("write ${r.name}", address, size, value)
        r.device.write(((address.toLong() and 0xffffffffL) - r.base).toInt(), size, value)
    }

    fun read8(address: Int) = read(address, 1)
    fun read16(address: Int) = read(address, 2)
    fun read32(address: Int) = read(address, 4)
    fun write8(address: Int, value: Int) = write(address, 1, value)
    fun write16(address: Int, value: Int) = write(address, 2, value)
    fun write32(address: Int, value: Int) = write(address, 4, value)
}
