package io.rebble.libpebblecommon.util

import kotlin.random.Random

fun randomCookie() = Random.nextInt(0, UShort.MAX_VALUE.toInt()).toUInt()