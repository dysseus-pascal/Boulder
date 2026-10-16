package boulder.server

import java.net.InetAddress

/** Ein Adressbereich wie 160.79.104.0/21 oder 2607:6bc0::/48. */
class Netz private constructor(private val basis: ByteArray, private val bits: Int) {

    fun enthaelt(adresse: String): Boolean {
        val a = literal(adresse) ?: return false
        if (a.size != basis.size) return false
        for (i in 0 until bits) {
            val maske = 0x80 ushr (i % 8)
            if ((a[i / 8].toInt() and maske) != (basis[i / 8].toInt() and maske)) return false
        }
        return true
    }

    override fun toString(): String = InetAddress.getByAddress(basis).hostAddress + "/" + bits

    companion object {
        fun aus(text: String): Netz {
            val (adresse, laenge) = text.split('/').let { it[0] to it.getOrNull(1) }
            val basis = requireNotNull(literal(adresse)) { "Kein Netz: $text" }
            val bits = laenge?.toInt() ?: (basis.size * 8)
            require(bits in 0..basis.size * 8) { "Kein Netz: $text" }
            return Netz(basis, bits)
        }

        // NUR ZAHLEN, KEINE NAMEN: InetAddress.getByName loeste einen Namen ueber DNS auf, und
        // ein Angreifer bestimmte so mit, welche Adresse geprueft wird.
        internal fun literal(text: String): ByteArray? {
            val t = text.trim().removePrefix("[").removeSuffix("]")
            val ok = Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(t) || (':' in t && Regex("""[0-9A-Fa-f:.]+""").matches(t))
            if (!ok) return null
            return runCatching { InetAddress.getByName(t).address }.getOrNull()
        }
    }
}
