package ir.v2max.app

import ir.v2max.app.util.BackupFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.io.StringReader

class BackupFilesTest {
    @Test fun `backup import accepts the limit and rejects larger input`() {
        assertEquals("سلام", BackupFiles.read(StringReader("سلام"), limit = 4))
        assertThrows(IOException::class.java) { BackupFiles.read(StringReader("سلام!"), limit = 4) }
    }
}
