package io.rebble.libpebblecommon.packets

import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.protocolhelpers.ProtocolEndpoint
import io.rebble.libpebblecommon.structmapper.SBytes
import io.rebble.libpebblecommon.structmapper.SUByte
import io.rebble.libpebblecommon.structmapper.SUInt
import io.rebble.libpebblecommon.structmapper.SUShort
import io.rebble.libpebblecommon.structmapper.SUUID
import io.rebble.libpebblecommon.structmapper.SUnboundBytes
import io.rebble.libpebblecommon.util.Endian

/**
 * Data logging packet. Little endian.
 */
sealed class DataLogging(command: Command) : PebblePacket(ProtocolEndpoint.DATA_LOG) {
    companion object {
        private val endianness = Endian.Little
    }
    val command = SUByte(m, command.value)
    enum class Command(val value: UByte) {
        OpenSession(0x01u),
        SendDataItems(0x02u),
        CloseSession(0x03u),
        ReportOpenSessions(0x84u),
        ACK(0x85u),
        NACK(0x86u),
        Timeout(0x07u),
        DumpAllData(0x88u),
        GetSendEnabled(0x89u),
        SendEnabledResponse(0x8Au),
        SetSendEnabled(0x8Bu),
    }

    enum class DataItemType(val value: UByte) {
        ByteArray(0x00u),
        UInt(0x01u),
        Int(0x02u),
        Invalid(0xFFu);

        companion object {
            fun fromValue(value: UByte): DataItemType {
                return entries.find { it.value == value } ?: Invalid
            }
        }
    }

    class OpenSession : DataLogging(Command.OpenSession) {
        val sessionId = SUByte(m)
        val applicationUUID = SUUID(m)
        val timestamp = SUInt(m, endianness = endianness)
        val tag = SUInt(m, endianness = endianness)
        val dataItemTypeId = SUByte(m)
        val dataItemType: DataItemType get() = DataItemType.fromValue(dataItemTypeId.get())
        val dataItemSize = SUShort(m, endianness = endianness)
    }

    class SendDataItems : DataLogging(Command.SendDataItems) {
        val sessionId = SUByte(m)
        val itemsLeftAfterThis = SUInt(m, endianness = endianness)
        val crc = SUInt(m, endianness = endianness)
        val payload = SUnboundBytes(m)
    }

    class CloseSession : DataLogging(Command.CloseSession) {
        val sessionId = SUByte(m)
    }

    class ReportOpenSessions(sessionIds: List<Byte>) : DataLogging(Command.ReportOpenSessions) {
        val sessions = SBytes(m, sessionIds.size, sessionIds.toByteArray().asUByteArray())
    }

    class ACK(sessionId: UByte = 0u) : DataLogging(Command.ACK) {
        val sessionId = SUByte(m, sessionId)
    }

    class NACK(sessionId: UByte = 0u) : DataLogging(Command.NACK) {
        val sessionId = SUByte(m, sessionId)
    }

    class Timeout : DataLogging(Command.Timeout) {
        val sessionId = SUByte(m)
    }

    class DumpAllData : DataLogging(Command.DumpAllData) {
        val sessionId = SUByte(m)
    }

    class GetSendEnabled : DataLogging(Command.GetSendEnabled)

    class SendEnabledResponse : DataLogging(Command.SendEnabledResponse) {
        val sendEnabledValue = SUByte(m)
        val sendEnabled: Boolean get() = sendEnabledValue.get() != 0u.toUByte()
    }

    class SetSendEnabled(enabled: Boolean) : DataLogging(Command.SetSendEnabled) {
        val sendEnabled = SUByte(m, if (enabled) 1u else 0u)
    }
}