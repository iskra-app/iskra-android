package wtf.iskra.features.support

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import wtf.iskra.core.transport.nostr.BridgeRefusal
import wtf.iskra.features.R

/** Строка ресурса и её единственный аргумент, если он есть: слово моста в «мост оборвал соединение: %s». */
data class Words(@StringRes val text: Int, val argument: Any? = null) {
    @Composable
    fun resolve(): String = if (argument != null) stringResource(text, argument) else stringResource(text)
}

/** Отказ моста словами человека (`Strings.Bridge.refusal` на iOS): совет про мост или сервер, а не про напечатанный адрес. */
fun BridgeRefusal.words(): Words = when (this) {
    BridgeRefusal.NotAllowed -> Words(R.string.bridge_refusal_not_allowed)
    BridgeRefusal.NotMatrix -> Words(R.string.bridge_refusal_not_matrix)
    BridgeRefusal.BadPort -> Words(R.string.bridge_refusal_bad_port)
    BridgeRefusal.NotPublic -> Words(R.string.bridge_refusal_not_public)
    BridgeRefusal.NoSuchHost -> Words(R.string.bridge_refusal_no_such_host)
    BridgeRefusal.Unreachable -> Words(R.string.bridge_refusal_unreachable)
    BridgeRefusal.Busy -> Words(R.string.bridge_refusal_busy)
    BridgeRefusal.Disagreement -> Words(R.string.bridge_refusal_disagreement)
    is BridgeRefusal.Broke -> Words(R.string.bridge_refusal_broke, reason)
}
