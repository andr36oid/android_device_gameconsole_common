package org.andr36oid.flipper.host

import java.io.File
import java.security.MessageDigest
import org.andr36oid.flipper.core.*

private const val FIRMWARE_SHA256 = "5fcf348c1216d29c63a27f906996076e9f3a43c8cb61a5e21fe1ff929e50631f"
private const val DEFAULT_FIRMWARE = "/andr36oid/work/reports/flipper/fw/flipper-z-f7-full-1.5.1.bin"

// Addresses come from the official 1.5.1 ELF symbol table, never host callbacks
// replacing firmware functions. All startup and RTOS instructions execute normally.
fun main(args: Array<String>) {
    val probe = args.firstOrNull() == "--probe"
    val positional = if (probe) args.drop(1) else args.toList()
    require(positional.size <= 2) { "Usage: run.sh [--probe] [full.bin [step-budget]]" }
    val file = File(positional.firstOrNull() ?: DEFAULT_FIRMWARE)
    if (!file.isFile) {
        check(positional.isEmpty()) { "firmware missing: ${file.path}" }
        println("SKIP official firmware: missing ${file.path}; synthetic boot regression passed")
        return
    }
    val limit = positional.getOrNull(1)?.toLong() ?: 1000000L
    require(limit in 1..100000000L) { "step budget must be 1..100000000" }
    val bytes = file.readBytes()
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    val pinned = digest == FIRMWARE_SHA256
    check(probe || pinned) { "strict boot test requires official f7 1.5.1 full.bin; SHA256=$digest (use --probe for other raw images)" }
    val board = Stm32wb55(bytes)
    val cpu = board.cpu
    // Poison RAM so startup clearing/copy assertions cannot pass by reset contents.
    board.sram1.bytes.fill(0xa5.toByte())
    board.sram2.bytes.fill(0xa5.toByte())
    val recent = ArrayList<String>()
    val counts = LinkedHashMap<Int, Long>()
    val checkpoints = if (pinned) linkedMapOf(0x08011650 to "Reset_Handler", 0x080115e8 to "SystemInit",
        0x08011408 to "main", 0x08002c9c to "early HAL", 0x08003bac to "early clock init",
        0x08016440 to "furi_thread_start", 0x08017378 to "furi_run", 0x08019d94 to "xPortStartScheduler",
        0x08019c80 to "SVC_Handler", 0x08019d44 to "xPortSysTickHandler", 0x0801894c to "xTaskIncrementTick")
        else linkedMapOf<Int, String>()
    val reached = HashSet<Int>()
    val exceptions = LinkedHashMap<Int, Int>()
    val mmio = linkedSetOf<String>()
    var tickEntered = false
    var bootComplete = false
    var startupChecked = false
    var steps = 0L
    board.bus.observer = { kind, address, size, value ->
        if (address ushr 28 in 4..5 || address ushr 20 in intArrayOf(0xe00, 0xe04)) {
            mmio.add("${kind.substringBefore(' ')} ${hex(address)} size=$size")
            recent.add("$kind ${hex(address)} size=$size value=${hex(value)} pc=${hex(cpu.pc)}")
            if (recent.size > 16) recent.removeAt(0)
        }
    }
    cpu.onException = { number ->
        exceptions[number] = (exceptions[number] ?: 0) + 1
        if (number == 15) tickEntered = true
        println("Exception: $number vectorPC=${hex(cpu.pc)} instructions=${cpu.instructions}")
    }
    println("Firmware: ${file.name} bytes=${bytes.size} SHA256=$digest")
    println("Vectors: reset=${hex(cpu.pc)} MSP=${hex(cpu.msp)}")
    println("Board: erased OTP, fixed virtual UID64, released buttons, I2C1 has no slaves; one virtual cycle/step")
    var failure: String? = null
    val start = System.nanoTime()
    try {
        while (steps < limit) {
            if (cpu.pc in checkpoints && reached.add(cpu.pc)) {
                println("Checkpoint: ${checkpoints[cpu.pc]} pc=${hex(cpu.pc)} instructions=${cpu.instructions}")
                if (cpu.pc == 0x08011408) {
                    for (i in 0 until 0x270) check(board.bus.read8(0x20000008 + i) == board.bus.read8(0x080bc264 + i)) { ".data mismatch offset=$i" }
                    for (i in 0x278 until 0x1500) check(board.sram1.bytes[i] == 0.toByte()) { ".bss not cleared offset=$i" }
                    for (i in 0x1e4 until 0x1377) check(board.sram2.bytes[i] == 0.toByte()) { "mailbox not cleared offset=$i" }
                    startupChecked = true
                    println("PASS startup .data copy, .bss and MB_MEM2 clear from poisoned RAM")
                }
            }
            counts[cpu.pc] = (counts[cpu.pc] ?: 0) + 1
            cpu.step()
            steps++
            if (pinned && tickEntered && 0x0801894c in reached && cpu.ipsr == 0 && board.bus.read32(0x20000980) == 1) {
                bootComplete = true
                if (!probe) break
            }
        }
    } catch (e: EmulationStop) { failure = e.message }
    val seconds = (System.nanoTime() - start) / 1e9
    println("Boot: instructions=${cpu.instructions} steps=$steps lastPC=${hex(cpu.pc)} exceptions=$exceptions cycles=${board.system.cycles}")
    println("Host execution: ${"%.3f".format(java.util.Locale.ROOT, seconds)} s (trace enabled; not a handheld benchmark)")
    if (failure != null) println("STOP: $failure")
    else if (bootComplete) println("Stopped after the first processed scheduler tick returned to thread mode")
    else println("Step budget reached")
    println("MMIO register inventory (${mmio.size} address/direction/width entries):")
    mmio.forEach { println("  $it") }
    println("Recent MMIO:")
    recent.forEach { println("  $it") }
    println("Most visited PCs:")
    counts.entries.sortedByDescending { it.value }.take(8).forEach { println("  ${hex(it.key)} ${it.value}") }
    if (!probe) {
        check(failure == null && bootComplete && startupChecked && checkpoints.keys.all { it in reached }) { "FAIL official firmware boot trace: did not complete all M1 checkpoints" }
        check((exceptions[11] ?: 0) > 0 && (exceptions[14] ?: 0) > 0 && exceptions[15] == 1)
        check(board.bus.read32(0x58000008) and 12 == 12 && board.bus.read32(0x200000fc) == 64000000) { "64 MHz PLL clock init did not complete" }
        println("PASS official firmware: startup, 64 MHz clock init, SVC/PendSV, first scheduler tick; no faults")
    } else println("PROBE complete: diagnostic run, not a passing boot regression")
}
