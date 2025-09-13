package io.rebble.libpebblecommon.util

import kotlin.time.Instant
import kotlinx.datetime.Instant as KtxInstant

fun Instant.asKtxInstant(): KtxInstant = KtxInstant.fromEpochSeconds(epochSeconds, nanosecondsOfSecond)
fun KtxInstant.asInstant(): Instant = Instant.fromEpochSeconds(epochSeconds, nanosecondsOfSecond)
