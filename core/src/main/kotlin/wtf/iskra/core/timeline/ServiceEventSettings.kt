package wtf.iskra.core.timeline

/**
 * Какие служебные строки ленты («Аня вошла», «Миша стал Михаилом») скрыты по просьбе человека — порт `ServiceEventSettings`.
 * Вид комнаты (название, тема, картинка, создание, шифрование) не скрывается никогда: это не шум, а история чата.
 */
data class ServiceEventSettings(
    val hidesNameChanges: Boolean = false,
    val hidesMembershipChanges: Boolean = false,
) {
    fun hides(kind: RoomEventKind): Boolean = when (kind) {
        is RoomEventKind.RenamedThemselves -> hidesNameChanges
        is RoomEventKind.Joined, is RoomEventKind.Left, is RoomEventKind.Invited, is RoomEventKind.Removed, is RoomEventKind.Banned -> hidesMembershipChanges
        is RoomEventKind.RoomRenamed, RoomEventKind.RoomTopicChanged, RoomEventKind.RoomPictureChanged, RoomEventKind.RoomCreated, RoomEventKind.EncryptionEnabled -> false
    }
}
