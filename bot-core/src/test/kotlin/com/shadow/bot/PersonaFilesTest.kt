package com.shadow.bot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PersonaFilesTest {
    private val card = PersonaCard("default", "日常", "小影", "自然交流，保留自己的观点。", true)
    private val settings = PersonaSettings("private-contact", 7, "default", false, listOf(card))

    @Test fun exportRoundTripPreservesOnlyPersonaAndAssignsFreshIdentity() {
        val json = PersonaFiles.encode(card)
        val restored = PersonaFiles.read(json.byteInputStream()).card
        assertEquals(card.copy(id = restored.id), restored)
        assertNotEquals(card.id, restored.id)
        val data = JSONObject(json).getJSONObject("data")
        assertEquals(setOf("name", "displayName", "instructions", "inheritIdentity"), data.keySet())
        val added = PersonaFiles.add(settings, restored)
        assertEquals(7, added.revision); assertFalse(added.contactEnabled)
        assertEquals("private-contact", added.entryId)
        assertEquals("日常 (2)", added.cards.last().name)
        assertEquals(added.cards.last().id, added.active)
    }
    @Test fun v2ImportsOnlyExpressionAndMarksFictionAndExamples() {
        val input = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"小舟","description":"旅行者","personality":"温和","scenario":"在月球","mes_example":"示例对白","first_mes":"假的开场白","system_prompt":"假的权限","character_book":{"entries":[]},"extensions":{"token":"假的秘密"}}}"""
        val result = PersonaFiles.decode(input)
        assertFalse(result.card.inheritIdentity)
        assertTrue(result.card.instructions.contains("虚构情境\n在月球"))
        assertTrue(result.card.instructions.contains("表达示例（非真实历史）"))
        assertFalse(result.card.instructions.contains("假的"))
        assertTrue(result.note.contains("未导入"))
    }
    @Test fun rejectsOversizeInvalidEncodingUnsupportedVersionAndWrongTypes() {
        assertThrows(IllegalArgumentException::class.java) { PersonaFiles.read(ByteArray(PersonaFiles.MAX_BYTES + 1).inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { PersonaFiles.read(byteArrayOf(0xC3.toByte(), 0x28).inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { PersonaFiles.decode(PersonaFiles.encode(card).replace("1.0", "9.0")) }
        val root = JSONObject(PersonaFiles.encode(card))
        root.getJSONObject("data").put("inheritIdentity", "false")
        assertThrows(IllegalArgumentException::class.java) { PersonaFiles.decode(root.toString()) }
        assertThrows(IllegalArgumentException::class.java) { PersonaFiles.encode(card.copy(instructions = "文".repeat(12001))) }
    }
    @Test fun duplicateDeleteAndValidationRespectDefaultAndOtherCards() {
        val copy = PersonaFiles.add(settings, card)
        assertEquals(settings, PersonaFiles.remove(copy, copy.active))
        assertThrows(IllegalArgumentException::class.java) { PersonaFiles.remove(settings, "default") }
        assertNotNull(PersonaFiles.validation(copy.copy(cards = copy.cards.map { it.copy(name = "重复") })))
        assertNotNull(PersonaFiles.validation(copy.copy(cards = copy.cards.map { if (it.id == "default") it.copy(displayName = "") else it })))
        var full = settings
        repeat(19) { full = PersonaFiles.add(full, card.copy(name = "卡".repeat(60))) }
        assertNull(PersonaFiles.validation(full))
        assertEquals(20, full.cards.map { it.name }.toSet().size)
        assertThrows(IllegalArgumentException::class.java) { PersonaFiles.add(full, card) }
    }
}
