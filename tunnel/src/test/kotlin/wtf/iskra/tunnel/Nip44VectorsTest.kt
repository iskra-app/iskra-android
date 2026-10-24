package wtf.iskra.tunnel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Векторы NIP-44 целиком — через биндинги (docs/PROMPT.md, 9.3). Сами векторы гоняет и `cargo test`;
 * здесь проверяется мост uniffi: что байты и строки доходят до Rust и обратно такими же, сообщение на
 * десятки килобайт включительно.
 */
class Nip44VectorsTest {
    private val v2 = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader!!.getResource("nip44.vectors.json")).readText(),
    ).jsonObject["v2"]!!.jsonObject

    private fun JsonObject.text(name: String) = this[name]!!.jsonPrimitive.content
    private fun JsonObject.bytes(name: String) = text(name).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    @Test
    fun conversationKeys() {
        for (case in v2["valid"]!!.jsonObject["get_conversation_key"]!!.jsonArray.map { it.jsonObject }) {
            assertContentEquals(case.bytes("conversation_key"), nip44ConversationKey(case.text("sec1"), case.text("pub2")))
        }
        for (case in v2["invalid"]!!.jsonObject["get_conversation_key"]!!.jsonArray.map { it.jsonObject }) {
            assertFailsWith<TunnelException> { nip44ConversationKey(case.text("sec1"), case.text("pub2")) }
        }
    }

    @Test
    fun encryptAndDecrypt() {
        for (case in v2["valid"]!!.jsonObject["encrypt_decrypt"]!!.jsonArray.map { it.jsonObject }) {
            val key = case.bytes("conversation_key")
            val payload = nip44EncryptWithNonce(key, case.bytes("nonce"), case.text("plaintext"))
            assertEquals(case.text("payload"), payload)
            assertEquals(case.text("plaintext"), nip44Decrypt(key, payload))
        }
    }

    /** Длинные — до потолка nostr 0.44.8 (65 408 байт); выше он отказывает, и это держит Rust-тест. */
    @Test
    fun longMessages() {
        for (case in v2["valid"]!!.jsonObject["encrypt_decrypt_long_msg"]!!.jsonArray.map { it.jsonObject }) {
            val plaintext = case.text("pattern").repeat(case["repeat"]!!.jsonPrimitive.long.toInt())
            assertContentEquals(case.bytes("plaintext_sha256"), sha(plaintext.toByteArray()))
            if (plaintext.toByteArray().size > 65_536 - 128) continue
            val payload = nip44EncryptWithNonce(case.bytes("conversation_key"), case.bytes("nonce"), plaintext)
            assertContentEquals(case.bytes("payload_sha256"), sha(payload.toByteArray()))
            assertEquals(plaintext, nip44Decrypt(case.bytes("conversation_key"), payload))
        }
    }

    @Test
    fun refusals() {
        for (case in v2["invalid"]!!.jsonObject["decrypt"]!!.jsonArray.map { it.jsonObject }) {
            assertFailsWith<TunnelException> { nip44Decrypt(case.bytes("conversation_key"), case.text("payload")) }
        }
    }
}
