package com.shadow.app

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shadow.bot.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun attachmentSize(bytes: Long) = if (bytes < 1024 * 1024) "${(bytes + 1023) / 1024} KB" else String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))

@Composable internal fun AttachmentCard(attachment: Attachment, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (attachment.mediaType.startsWith("image/")) Icons.Outlined.AccountBox else Icons.AutoMirrored.Outlined.List, null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(attachment.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${attachmentSize(attachment.bytes)} · ${if (attachment.mediaType.startsWith("image/")) "查看图片" else "查看文件"}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable internal fun AttachmentDrafts(attachments: List<AttachmentDraft>, vm: BotViewModel) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(attachments, key = { it.id }) { draft ->
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable(enabled = draft.uploaded != null) { draft.uploaded?.let(vm::previewAttachment) }.padding(vertical = 10.dp)) {
                        Text(draft.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when (draft.status) {
                            "ready" -> "${attachmentSize(draft.uploaded?.bytes ?: 0)} · 已上传，待发送"
                            "failed" -> draft.error ?: "上传失败，请重试"
                            "uploading" -> "正在上传…"
                            else -> "等待上传…"
                        }, style = MaterialTheme.typography.labelSmall, color = if (draft.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (draft.status == "failed") IconButton(onClick = { vm.retryAttachment(draft.id) }) { Icon(Icons.Outlined.Refresh, "重试上传 ${draft.name}", Modifier.size(20.dp)) }
                    if (draft.status == "uploading") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    IconButton(onClick = { vm.removeAttachment(draft.id) }) { Icon(Icons.Outlined.Close, "移除附件 ${draft.name}", Modifier.size(20.dp)) }
                }
            }
        }
    }
}

@Composable internal fun AttachmentPreviewScreen(state: ScreenState, vm: BotViewModel) {
    val preview = state.attachmentPreview
    var saving by remember { mutableStateOf<AttachmentPreviewData?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) saving?.let { vm.saveAttachment(uri, it) }
        saving = null
    }
    if (preview == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (state.attachmentLoading) "正在读取附件…" else "附件暂不可用，请返回后重试。", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val bitmap by produceState<ImageBitmap?>(null, preview.attachment.id) {
        if (preview.attachment.mediaType.startsWith("image/")) value = withContext(Dispatchers.Default) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(preview.bytes, 0, preview.bytes.size, bounds)
                val options = BitmapFactory.Options().apply {
                    var sample = 1
                    while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                    inSampleSize = sample
                }
                BitmapFactory.decodeByteArray(preview.bytes, 0, preview.bytes.size, options)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(preview.attachment.name, style = MaterialTheme.typography.titleLarge)
        Text("${attachmentSize(preview.attachment.bytes)} · ${preview.attachment.mediaType}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            bitmap?.let { Image(it, preview.attachment.name, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
                ?: Text(if (preview.attachment.mediaType.startsWith("image/")) "图片暂无法预览，可以保存原件查看。" else "原件已上传到 Life，可保存后使用相应应用打开。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = { saving = preview; save.launch(preview.attachment.name) }, modifier = Modifier.fillMaxWidth()) { Text("保存原件") }
    }
}
