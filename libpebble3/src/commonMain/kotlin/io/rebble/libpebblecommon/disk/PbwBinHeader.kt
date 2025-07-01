package io.rebble.libpebblecommon.disk

import io.rebble.libpebblecommon.packets.blobdb.AppMetadata
import io.rebble.libpebblecommon.structmapper.*
import io.rebble.libpebblecommon.util.DataBuffer
import io.rebble.libpebblecommon.util.Endian

class PbwBinHeader() : StructMappable() {
    /**
     * Major header version.
     */
    val headerVersionMajor: SUByte = SUByte(m)

    /**
     * Minor header version.
     */
    val headerVersionMinor: SUByte = SUByte(m)

    /**
     * Major sdk version.
     */
    val sdkVersionMajor: SUByte = SUByte(m)

    /**
     * Minor sdk version.
     */
    val sdkVersionMinor: SUByte = SUByte(m)

    /**
     * Major app version.
     */
    val appVersionMajor: SUByte = SUByte(m)

    /**
     * Minor app version.
     */
    val appVersionMinor: SUByte = SUByte(m)

    /**
     * Size of the app payload in bytes
     */
    val appSize: SUShort = SUShort(m)

    /**
     * ??? (Presumably offset where app payload starts?)
     */
    val appOffset: SUInt = SUInt(m)

    /**
     * CRC checksum of the app payload
     */
    val crc: SUInt = SUInt(m)

    /**
     * Name of the app
     */
    val appName: SFixedString = SFixedString(m, 32)

    /**
     * Name of the company that made the app
     */
    val companyName: SFixedString = SFixedString(m, 32)

    /**
     * Resource ID of the primary icon.
     */
    val icon: SUInt = SUInt(m, endianness = Endian.Little)

    /**
     * ???
     */
    val symbolTableAddress: SUInt = SUInt(m)

    /**
     * List of app install flags. Should be forwarded to the watch when inserting into BlobDB.
     */
    val flags: SUInt = SUInt(m, endianness = Endian.Little)

    /**
     * ???
     */
    val numRelocationListEntries: SUInt = SUInt(m)

    /**
     * UUID of the app
     */
    val uuid: SUUID = SUUID(m)

    companion object {
        const val SIZE: Int = 8 + 2 + 2 + 2 + 2 + 4 + 4 + 32 + 32 + 4 + 4 + 4 + 4 + 16

        /**
         * Parse existing Pbw binary payload header. You should read [SIZE] bytes from the binary
         * payload and pass it into this method.
         *
         * @throws IllegalArgumentException if header is not valid pebble app header
         */
        fun parseFileHeader(data: UByteArray): PbwBinHeader {
            if (data.size != SIZE) {
                throw IllegalArgumentException(
                    "Read data from the file should be exactly $SIZE bytes"
                )
            }

            val buffer = DataBuffer(data)

            val sentinel = buffer.getBytes(8)
            if (!sentinel.contentEquals(EXPECTED_SENTINEL)) {
                throw IllegalArgumentException("Sentinel does not match")
            }

            return PbwBinHeader().also {
                it.fromBytes(buffer)
            }
        }

        /**
         * First 8 bytes of the header, spelling the word "PBLAPP" in ASCII,
         * followed by two zeros.
         */
        private val EXPECTED_SENTINEL = ubyteArrayOf(
            0x50u, 0x42u, 0x4Cu, 0x41u, 0x50u, 0x50u, 0x00u, 0x00u
        )
    }
}