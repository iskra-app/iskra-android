package wtf.iskra.core.notifications

import org.matrix.rustcomponents.sdk.Action
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.PushCondition
import org.matrix.rustcomponents.sdk.RuleKind
import org.matrix.rustcomponents.sdk.Tweak
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.withTimeLimit
import kotlin.time.Duration.Companion.seconds

/**
 * Попросить сервер присылать пуши о реакциях. Порт `ReactionPushRule.swift`.
 *
 * По умолчанию сервер их проглатывает правилом `.m.rule.reaction`. Правило пользователя типа override
 * проверяется раньше правил сервера, и это — тихое уведомление о каждой реакции. Лишнее (реакции на
 * чужие сообщения) отбрасывается на устройстве, в [PushDecoder]: правило пушей не видит, на чьё
 * сообщение реакция.
 */
internal object ReactionPushRule {
    /**
     * Старый префикс оставлен нарочно: правило живёт на сервере и **общее с iOS**. Переименовать —
     * оставить на аккаунте дубль, который никто никогда не уберёт.
     */
    const val RULE_ID = "moscow.swag.iskra.reactions"

    /** Идемпотентно и при каждом входе. Не вышло — только в лог: реакции без пушей — не повод ничего ломать. */
    suspend fun enable(client: Client) {
        try {
            withTimeLimit(LIMIT) {
                val settings = client.getNotificationSettings()
                try {
                    settings.setCustomPushRule(
                        ruleId = RULE_ID,
                        ruleKind = RuleKind.Override,
                        // Пустой звук — тихое уведомление: реакция — новость, но не тревога.
                        actions = listOf(Action.Notify, Action.SetTweak(Tweak.Sound(""))),
                        conditions = listOf(PushCondition.EventMatch(key = "type", pattern = "m.reaction")),
                    )
                } finally {
                    settings.close()
                }
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.error(Log.Category.PUSH, "the reaction push rule was not set: ${Log.describe(error)}")
        }
    }

    private val LIMIT = 15.seconds
}
