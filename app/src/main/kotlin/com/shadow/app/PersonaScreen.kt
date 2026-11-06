package com.shadow.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shadow.bot.*

@Composable internal fun PersonaEditor(state: ScreenState, vm: BotViewModel) {
    val draft = state.personaDraft ?: state.persona
    if (draft == null) { Text("正在读取人设…", Modifier.padding(24.dp)); return }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::importPersona)
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), vm::exportPersona)
    PersonaForm(draft, state.bot.busy, vm::editPersona, vm::savePersona,
        onImport = { vm.preparePersonaImport(); importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
        onExport = { card -> vm.preparePersonaExport(card); exporter.launch("shadow-persona.json") },
        onReload = { vm.navigate(Destination.Persona) })
}

@Composable internal fun PersonaForm(draft: PersonaSettings, busy: Boolean, onChange: (PersonaSettings) -> Unit,
    onSave: (PersonaSettings) -> Unit, onImport: () -> Unit, onExport: (PersonaCard) -> Unit, onReload: () -> Unit) {
    val card = draft.cards.first { it.id == draft.active }
    val validation = PersonaFiles.validation(draft)
    fun update(change: (PersonaCard) -> PersonaCard) = onChange(draft.copy(cards = draft.cards.map { if (it.id == card.id) change(it) else it }))
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("人设可以切换，当前会话与已有关系继续保留。修改在保存后生效。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImport, enabled = !busy && draft.cards.size < 20) { Text("导入人设") }
            OutlinedButton(onClick = { onExport(card) }, enabled = !busy && validation == null) { Text("导出当前卡") }
        } }
        items(draft.cards, key = { it.id }) { choice ->
            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { onChange(draft.copy(active = choice.id)) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = draft.active == choice.id, enabled = !busy, onClick = { onChange(draft.copy(active = choice.id)) })
                Text(choice.name, Modifier.weight(1f))
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { onChange(PersonaFiles.add(draft, PersonaCard("new", "自定义", card.displayName, "", false))) }, enabled = !busy && draft.cards.size < 20) { Text("新增") }
            TextButton(onClick = { onChange(PersonaFiles.add(draft, card)) }, enabled = !busy && draft.cards.size < 20) { Text("复制当前卡") }
            TextButton(onClick = { onChange(PersonaFiles.remove(draft, card.id)) }, enabled = !busy && card.id != "default") { Text("移除") }
        } }
        item { OutlinedTextField(card.name, { value -> update { it.copy(name = value.take(60)) } }, enabled = !busy, label = { Text("人设卡名称") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(card.displayName, { value -> update { it.copy(displayName = value.take(100)) } }, enabled = !busy, label = { Text("显示名字") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(card.instructions, { value -> update { it.copy(instructions = value.take(12000)) } }, enabled = !busy, label = { Text("性格、表达与轻量角色设定") }, supportingText = { Text("${card.instructions.length} / 12000") }, minLines = 6, modifier = Modifier.fillMaxWidth()) }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Switch(card.inheritIdentity, { value -> update { it.copy(inheritIdentity = value) } }, enabled = !busy); Text("沿用原身份设定", Modifier.padding(start = 12.dp)) } }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Switch(draft.contactEnabled, { onChange(draft.copy(contactEnabled = it)) }, enabled = !busy); Text("允许主动聊天", Modifier.padding(start = 12.dp)) } }
        if (validation != null) item { Text(validation, color = MaterialTheme.colorScheme.error) }
        item { Button(onClick = { onSave(draft) }, enabled = !busy && validation == null, modifier = Modifier.fillMaxWidth()) { Text("保存人设") } }
        item { TextButton(onClick = onReload, enabled = !busy) { Text("放弃修改并重新读取") } }
    }
}
