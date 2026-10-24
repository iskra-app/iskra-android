package wtf.iskra.core.storage

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Сколько места занимает каталог — то, что экран «Хранилище» складывает в число. */
class StorageStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun write(directory: File, name: String, bytes: Int) {
        File(directory, name).apply { parentFile.mkdirs() }.writeBytes(ByteArray(bytes))
    }

    @Test
    fun aDirectoryTreeIsTheSumOfItsFiles() {
        val root = folder.newFolder("cache")
        write(root, "a", 100)
        write(root, "deep/b", 250)
        write(root, "deep/er/c", 50)
        assertEquals(400L, StorageStore.size(root))
    }

    @Test
    fun aMissingDirectoryIsNothingNotAnError() {
        assertEquals(0L, StorageStore.size(File(folder.root, "absent")))
    }

    @Test
    fun theButtonNamesWhatItWinsBackIncludingTheIndex() {
        val state = StorageStore.State(
            amounts = listOf(
                StorageAmount(StorageKind.SESSION, 4_000),
                StorageAmount(StorageKind.CACHE, 480_000),
                StorageAmount(StorageKind.SEARCH, 20_000),
            ),
        )
        assertEquals(504_000L, state.total)
        assertEquals(500_000L, state.reclaimable)
        assertEquals(20_000L, state.searchIndexBytes)
    }
}
