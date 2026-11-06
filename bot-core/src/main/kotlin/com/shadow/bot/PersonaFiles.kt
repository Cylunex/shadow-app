package com.shadow.bot

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

data class ImportedPersona(val card: PersonaCard, val note: String)

/** Portable expression settings only: no contact IDs, history, permissions or memory. */
object PersonaFiles {
    const val MAX_BYTES = 256 * 1024
    private val idPattern = Regex("[a-z][a-z0-9_-]{0,47}")
    fun read(input: InputStream): ImportedPersona {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "人设文件不能超过 256 KiB。" }
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF") }
        catch (_: Exception) { throw IllegalArgumentException("请选择 UTF-8 编码的 JSON 人设文件。") }
        return decode(text)
    }
    fun decode(text: String): ImportedPersona {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "人设文件不能超过 256 KiB。" }
        val root = try { JSONObject(text) } catch (_: Exception) { throw IllegalArgumentException("无法读取人设，请选择 JSON 人设卡。") }
        fun string(row: JSONObject, key: String, optional: Boolean = false): String {
            if (optional && !row.has(key)) return ""
            return row.opt(key) as? String ?: throw IllegalArgumentException("人设字段 $key 必须是文字。")
        }
        val card: PersonaCard
        val note: String
        when (root.optString("spec")) {
            "shadow_persona" -> {
                require(root.opt("spec_version") == "1.0") { "暂不支持这个人设文件版本。" }
                val row = root.optJSONObject("data") ?: throw IllegalArgumentException("人设文件缺少资料。")
                require(row.opt("inheritIdentity") is Boolean) { "沿用身份选项必须为布尔值。" }
                card = PersonaCard(newId(), string(row, "name"), string(row, "displayName"), string(row, "instructions"), row.getBoolean("inheritIdentity"))
                note = "已加入编辑草稿，保存后生效。"
            }
            "chara_card_v2" -> {
                require(root.opt("spec_version") == "2.0") { "暂不支持这个角色卡版本。" }
                val row = root.optJSONObject("data") ?: throw IllegalArgumentException("角色卡缺少资料。")
                val name = string(row, "name")
                val sections = listOf("description" to "角色描述", "personality" to "性格", "scenario" to "虚构情境", "mes_example" to "表达示例（非真实历史）")
                val instructions = sections.mapNotNull { (key, label) -> string(row, key, true).takeIf { it.isNotBlank() }?.let { "$label\n$it" } }.joinToString("\n\n")
                card = PersonaCard(newId(), name, name, instructions, false)
                note = "已导入名字、描述、性格、情境与表达示例。开场白、系统提示、世界书和扩展未导入；保存后生效。"
            }
            else -> throw IllegalArgumentException("支持 Shadow 人设 JSON 和 Character Card V2 JSON。")
        }
        require(card.name.isNotBlank() && card.name.length <= 60 && card.displayName.isNotBlank() && card.displayName.length <= 100 && card.instructions.length <= 12000) {
            "卡片名称最多 60 字，显示名字最多 100 字，设定最多 12000 字；名字不能为空。"
        }
        return ImportedPersona(card.copy(name = card.name.trim(), displayName = card.displayName.trim()), note)
    }
    fun encode(card: PersonaCard): String {
        require(validation(PersonaSettings("export", 0, "default", true, listOf(card.copy(id = "default")))) == null) { "请先修正人设资料。" }
        return JSONObject().put("spec", "shadow_persona").put("spec_version", "1.0").put("data", JSONObject()
            .put("name", card.name.trim()).put("displayName", card.displayName.trim()).put("instructions", card.instructions)
            .put("inheritIdentity", card.inheritIdentity)).toString(2)
    }
    fun add(settings: PersonaSettings, card: PersonaCard): PersonaSettings {
        require(settings.cards.size < 20) { "最多保留 20 张人设卡，请先移除不用的卡片。" }
        val names = settings.cards.map { it.name.trim() }.toSet()
        var name = card.name.trim().take(60)
        var suffix = 2
        while (name in names) { val tail = " ($suffix)"; name = card.name.trim().take(60 - tail.length) + tail; suffix++ }
        val added = card.copy(id = newId(), name = name)
        return settings.copy(active = added.id, cards = settings.cards + added)
    }
    fun remove(settings: PersonaSettings, id: String): PersonaSettings {
        require(id != "default") { "默认人设需要保留。" }
        return settings.copy(active = if (settings.active == id) "default" else settings.active, cards = settings.cards.filterNot { it.id == id })
    }
    fun validation(settings: PersonaSettings): String? = when {
        settings.cards.size !in 1..20 -> "请保留 1 至 20 张人设卡。"
        settings.cards.none { it.id == "default" } || settings.cards.none { it.id == settings.active } -> "请保留默认人设并选择当前人设。"
        settings.cards.any { !idPattern.matches(it.id) } || settings.cards.map { it.id }.toSet().size != settings.cards.size -> "人设标识无效或重复。"
        settings.cards.any { it.name.isBlank() || it.name.length > 60 || it.displayName.isBlank() || it.displayName.length > 100 || it.instructions.length > 12000 } -> "请检查所有卡片：名称 1–60 字、显示名字 1–100 字、设定最多 12000 字。"
        settings.cards.map { it.name.trim() }.toSet().size != settings.cards.size -> "人设卡名称不能重复。"
        else -> null
    }
    private fun newId() = "custom-" + UUID.randomUUID().toString()
}
