package io.rebble.libpebblecommon.services.appmessage

import kotlinx.atomicfu.atomic

class AppMessageTransactionSequence: Sequence<Byte> {
    private val sequence = atomic(0)

    override fun iterator(): Iterator<Byte> {
        if (sequence.value != 0) {
            error("Sequence can only be iterated once")
        }
        return object : Iterator<Byte> {
            override fun hasNext(): Boolean = true
            override fun next(): Byte {
                sequence.compareAndSet(0x100, 0)
                return (sequence.getAndIncrement() and 0xff).toByte()
            }
        }
    }

}