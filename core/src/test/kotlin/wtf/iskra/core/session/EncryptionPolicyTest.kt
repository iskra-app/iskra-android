package wtf.iskra.core.session

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.matrix_sdk_crypto.CollectStrategy
import uniffi.matrix_sdk_crypto.TrustRequirement

/**
 * Политика доверия решена с автором 2026-09-30 (docs/PROMPT.md, 8.4) и мимоходом не меняется: тест
 * делает «мимоходом» невозможным, а не только неприличным.
 */
class EncryptionPolicyTest {
    @Test
    fun `ключи — всем устройствам, пока проверенный человек не перестанет сходиться`() {
        assertEquals(CollectStrategy.ERROR_ON_VERIFIED_USER_PROBLEM, EncryptionPolicy.recipients)
    }

    @Test
    fun `сообщения читаются от кого бы ни пришли`() {
        assertEquals(TrustRequirement.UNTRUSTED, EncryptionPolicy.decryption.senderDeviceTrustRequirement)
    }
}
