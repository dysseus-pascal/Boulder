package io.rebble.libpebblecommon.packets.blobdb

import io.rebble.libpebblecommon.packets.blobdb.TimelineItem.Attribute
import io.rebble.libpebblecommon.util.PebbleColor
import io.rebble.libpebblecommon.util.TimelineAttributeFactory
import kotlin.uuid.Uuid

class ActionBuilder internal constructor() {
    var actionID: UByte = 0u
    var type = TimelineItem.Action.Type.Empty
    private var attributes = listOf<Attribute>()
    fun attributes(block: AttributesListBuilder.() -> Unit) {
        val builder = AttributesListBuilder()
        builder.block()
        attributes = builder.build()
    }

    fun build(): TimelineItem.Action {
        return TimelineItem.Action(actionID, type, attributes)
    }
}

class ActionsListBuilder internal constructor() {
    private val actions = mutableListOf<TimelineItem.Action>()

    fun action(block: ActionBuilder.() -> Unit) {
        val builder = ActionBuilder()
        builder.block()
        actions.add(builder.build())
    }

    internal fun build(): List<TimelineItem.Action> {
        return actions
    }
}

class AttributesListBuilder internal constructor() {
    private val attributes = mutableListOf<Attribute>()

    fun title(block: () -> String) {
        attributes.add(TimelineAttributeFactory.title(block()))
    }

    fun subtitle(block: () -> String) {
        attributes.add(TimelineAttributeFactory.subtitle(block()))
    }

    fun body(block: () -> String) {
        attributes.add(TimelineAttributeFactory.body(block()))
    }

    fun icon(block: () -> TimelineIcon) {
        attributes.add(TimelineAttributeFactory.icon(block()))
    }

    fun tinyIcon(block: () -> TimelineIcon) {
        attributes.add(TimelineAttributeFactory.tinyIcon(block()))
    }

    fun smallIcon(block: () -> TimelineIcon) {
        attributes.add(TimelineAttributeFactory.smallIcon(block()))
    }

    fun largeIcon(block: () -> TimelineIcon) {
        attributes.add(TimelineAttributeFactory.largeIcon(block()))
    }

    fun cannedResponse(block: () -> List<String>) {
        attributes.add(TimelineAttributeFactory.cannedResponse(block()))
    }

    fun sender(block: () -> String) {
        attributes.add(TimelineAttributeFactory.sender(block()))
    }

    fun primaryColor(block: () -> PebbleColor) {
        attributes.add(TimelineAttributeFactory.primaryColor(block()))
    }

    fun timestamp(block: () -> UInt) {
        attributes.add(TimelineAttributeFactory.timestamp(block()))
    }

    internal fun build(): List<Attribute> {
        return attributes
    }
}

class FlagsBuilder internal constructor() {
    private val flags = mutableListOf<TimelineItem.Flag>()

    fun isVisible() {
        flags.add(TimelineItem.Flag.IS_VISIBLE)
    }

    fun isFloating() {
        flags.add(TimelineItem.Flag.IS_FLOATING)
    }

    fun isAllDay() {
        flags.add(TimelineItem.Flag.IS_ALL_DAY)
    }

    fun fromWatch() {
        flags.add(TimelineItem.Flag.FROM_WATCH)
    }

    fun fromANCS() {
        flags.add(TimelineItem.Flag.FROM_ANCS)
    }

    fun persistQuickView() {
        flags.add(TimelineItem.Flag.PERSIST_QUICK_VIEW)
    }


    internal fun build(): UShort {
        return TimelineItem.Flag.makeFlags(flags)
    }
}

class TimelineItemBuilder internal constructor(val itemID: Uuid) {
    var parentId: Uuid = Uuid.NIL
    var timestamp: UInt = 0u
    var duration: UShort = 0u
    var type: TimelineItem.Type = TimelineItem.Type.Notification
    private var flags: UShort = 0u
    var layout: TimelineItem.Layout = TimelineItem.Layout.GenericPin
    private var attributes = listOf<Attribute>()
    private var actions = listOf<TimelineItem.Action>()

    fun attributes(block: AttributesListBuilder.() -> Unit) {
        val builder = AttributesListBuilder()
        builder.block()
        attributes = builder.build()
    }

    fun actions(block: ActionsListBuilder.() -> Unit) {
        val builder = ActionsListBuilder()
        builder.block()
        actions = builder.build()
    }

    fun flags(block: FlagsBuilder.() -> Unit) {
        val builder = FlagsBuilder()
        builder.block()
        flags = builder.build()
    }

    internal fun build() = TimelineItem(
        itemID,
        parentId,
        timestamp,
        duration,
        type,
        flags,
        layout,
        attributes,
        actions
    )
}

fun buildTimelineItem(itemId: Uuid, block: TimelineItemBuilder.() -> Unit): TimelineItem {
    val builder = TimelineItemBuilder(itemId)
    builder.block()
    return builder.build()
}

fun buildNotificationItem(itemId: Uuid, block: TimelineItemBuilder.() -> Unit): TimelineItem {
    val builder = TimelineItemBuilder(itemId)
    builder.type = TimelineItem.Type.Notification
    builder.layout = TimelineItem.Layout.GenericNotification
    builder.block()
    return builder.build()
}