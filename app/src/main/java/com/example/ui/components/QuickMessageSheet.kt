package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ChatMessage
import com.example.ui.theme.BlueAccent
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.SlateCard
import com.example.ui.theme.SlateCardBorder
import com.example.ui.theme.SlateDark
import com.example.ui.theme.SlateLight
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickMessageSheet(
    messages: List<ChatMessage>,
    isConnected: Boolean,
    onSendMessage: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var textInput by remember { mutableStateOf("") }

    val quickPings = listOf(
        "Radio check! 🔊",
        "Loud and clear! 👍",
        "Roger that 10-4",
        "Stand by ⏳",
        "Mic test 1, 2, 3 🎙️",
        "Say again please?"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SlateDark,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(SlateLight)
            )
        },
        modifier = modifier.testTag("quick_message_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Forum,
                        contentDescription = "Chat",
                        tint = CyanNeon,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Bluetooth Radio Messages",
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isConnected) "Connected - Direct RFCOMM text channel" else "Connect to a phone to exchange messages",
                            color = if (isConnected) CyanNeon else TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_chat_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Quick Ping chips
            Text(
                text = "QUICK RADIO PINGS",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                quickPings.forEach { ping ->
                    AssistChip(
                        onClick = {
                            if (isConnected) onSendMessage(ping)
                        },
                        label = { Text(ping, fontSize = 11.sp, color = TextPrimary) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = SlateCard),
                        border = AssistChipDefaults.assistChipBorder(borderColor = SlateCardBorder, enabled = true)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Message History
            Text(
                text = "MESSAGE LOG (${messages.size})",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(6.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(SlateSurface, RoundedCornerShape(10.dp))
                    .padding(8.dp),
                reverseLayout = true
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No messages yet. Send a radio check ping!",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                } else {
                    items(messages.reversed()) { msg ->
                        MessageBubbleRow(message = msg)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Input Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("Type radio text message...", color = TextSecondary, fontSize = 13.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = CyanNeon,
                        unfocusedBorderColor = SlateCardBorder,
                        focusedContainerColor = SlateCard,
                        unfocusedContainerColor = SlateCard
                    ),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_text_input")
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        if (textInput.isNotBlank() && isConnected) {
                            onSendMessage(textInput.trim())
                            textInput = ""
                        }
                    },
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (isConnected && textInput.isNotBlank()) BlueAccent else SlateLight)
                        .testTag("send_message_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (isConnected && textInput.isNotBlank()) Color.White else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun MessageBubbleRow(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val formattedTime = remember(message.timestamp) { timeFormat.format(Date(message.timestamp)) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = if (message.isFromMe) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (message.isFromMe) BlueAccent else SlateCard
            ),
            shape = RoundedCornerShape(
                topStart = 12.dp,
                topEnd = 12.dp,
                bottomStart = if (message.isFromMe) 12.dp else 2.dp,
                bottomEnd = if (message.isFromMe) 2.dp else 12.dp
            ),
            border = if (!message.isFromMe) androidx.compose.foundation.BorderStroke(1.dp, SlateCardBorder) else null
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message.senderName,
                        color = if (message.isFromMe) Color.White.copy(alpha = 0.85f) else CyanNeon,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = formattedTime,
                        color = if (message.isFromMe) Color.White.copy(alpha = 0.6f) else TextSecondary,
                        fontSize = 9.sp
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = message.message,
                    color = Color.White,
                    fontSize = 13.sp
                )
            }
        }
    }
}
