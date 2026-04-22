package coredevices.util.transcription

import android.os.Process
import android.os.Process.THREAD_PRIORITY_URGENT_AUDIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


actual suspend fun withHighPriorityThread(block: suspend () -> Unit) {
    withContext(Dispatchers.Default.limitedParallelism(1)) {
        val originalPriority = Process.getThreadPriority(Process.myTid())
        Process.setThreadPriority(THREAD_PRIORITY_URGENT_AUDIO)
        try {
            block()
        } finally {
            Process.setThreadPriority(originalPriority)
        }
    }
}