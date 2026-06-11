package coredevices.ring

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import coredevices.ring.agent.ShareActionHandler
import coredevices.util.CoreConfigFlow
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Invisible share-sheet target that turns shared text into an Index note or reminder and
 * finishes immediately. The concrete subclasses exist so each action gets its own share
 * target.
 */
abstract class ShareToIndexActivity(
    private val action: ShareActionHandler.Action,
) : Activity(), KoinComponent {
    private val shareActionHandler: ShareActionHandler by inject()
    private val coreConfigFlow: CoreConfigFlow by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getStringExtra(Intent.EXTRA_TEXT)
        if (coreConfigFlow.value.enableIndex && !text.isNullOrBlank()) {
            shareActionHandler.handleSharedText(text, action)
            val message = when (action) {
                ShareActionHandler.Action.Note -> R.string.share_to_index_note_done
                ShareActionHandler.Action.Reminder -> R.string.share_to_index_reminder_done
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}

class ShareToIndexNoteActivity : ShareToIndexActivity(ShareActionHandler.Action.Note)
class ShareToIndexReminderActivity : ShareToIndexActivity(ShareActionHandler.Action.Reminder)
