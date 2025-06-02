package io.rebble.libpebblecommon.calls

sealed interface Call {
    val contactName: String?
    val contactNumber: String

    sealed interface EndableCall: Call {
        fun endCall()
    }
    sealed interface AnswerableCall: Call {
        fun answerCall()
    }

    class RingingCall(
        override val contactName: String?,
        override val contactNumber: String,
        private val onCallEnd: (EndableCall) -> Unit,
        private val onCallAnswer: (AnswerableCall) -> Unit
    ): EndableCall, AnswerableCall {
        override fun endCall() {
            onCallEnd(this)
        }

        override fun answerCall() {
            onCallAnswer(this)
        }
    }

    class DialingCall(
        override val contactName: String?,
        override val contactNumber: String,
        private val onCallEnd: (EndableCall) -> Unit,
    ): EndableCall {
        override fun endCall() {
            onCallEnd(this)
        }
    }

    class ActiveCall(
        override val contactName: String?,
        override val contactNumber: String,
        private val onCallEnd: (EndableCall) -> Unit,
    ): EndableCall {
        override fun endCall() {
            onCallEnd(this)
        }
    }

    class HoldingCall(
        override val contactName: String?,
        override val contactNumber: String,
        private val onCallEnd: (EndableCall) -> Unit,
    ): EndableCall {
        override fun endCall() {
            onCallEnd(this)
        }
    }
}