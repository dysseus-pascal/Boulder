package coredevices.ring.ui.components.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coredevices.ring.ui.PreviewWrapper
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun ChatInput(modifier: Modifier = Modifier, onMicClick: () -> Unit = {}, onTextSubmit: ((String) -> Unit)? = null) {
    val interactionSource = remember { MutableInteractionSource() }
    var inputText by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    fun clearAndDismiss() {
        inputText = ""
        focusManager.clearFocus()
    }
    Box(
        modifier = Modifier
            .border(2.dp, MaterialTheme.colorScheme.inversePrimary, MaterialTheme.shapes.large)
            .height(48.dp)
            .then(modifier)
    ) {
        TextField(
            enabled = onTextSubmit != null,
            interactionSource = interactionSource,
            value = inputText,
            modifier = Modifier.fillMaxWidth().onFocusChanged {
                focused = it.isFocused
            },
            onValueChange = { newText -> inputText = newText },
            placeholder = { Text("Add to Index...", style = MaterialTheme.typography.bodySmall) },
            trailingIcon = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AnimatedContent(targetState = focused) { focused ->
                        if (focused) {
                            IconButton(onClick = ::clearAndDismiss) { Icon(Icons.Filled.Clear, "Clear") }
                        } else {
                            IconButton(onClick = onMicClick) { Icon(Icons.Filled.Mic, "Speech Input") }
                        }
                    }
                }
            },
            shape = MaterialTheme.shapes.large,
            singleLine = true,
            keyboardActions = KeyboardActions(
                onSend = {
                    onTextSubmit?.invoke(inputText)
                    inputText = ""
                }
            ),
            keyboardOptions = KeyboardOptions.Default.copy(
                imeAction = ImeAction.Send
            ),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent
            ),
            textStyle = MaterialTheme.typography.bodySmall
        )

    }
}

@Preview
@Composable
fun ChatInputPreview() {
    PreviewWrapper {
        ChatInput()
    }
}