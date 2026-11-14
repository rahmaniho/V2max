package ir.v2max.app

import ir.v2max.app.core.ExternalControlPolicy
import ir.v2max.app.data.AppSettings
import ir.v2max.app.data.Migrations
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalControlPolicyTest {
    @Test fun `external automation is disabled by default and requires explicit opt in`() {
        assertFalse(ExternalControlPolicy.allowed(AppSettings()))
        assertTrue(ExternalControlPolicy.allowed(AppSettings(allowExternalAutomation = true)))
    }

    @Test fun `app lock denies external automation even if an old setting enables it`() {
        assertFalse(ExternalControlPolicy.allowed(AppSettings(appLock = true, allowExternalAutomation = true)))
    }

    @Test fun `old stored settings and backups do not silently enable automation`() {
        val stored = requireNotNull(Migrations.heal("""{"appLock":false}""", AppSettings()))
        assertFalse(ExternalControlPolicy.allowed(stored))
        val backup = requireNotNull(Migrations.decodeBackup("""{"version":2,"settings":{"appLock":false}}"""))
        assertFalse(ExternalControlPolicy.allowed(backup.settings))
        val forgedSettings = requireNotNull(Migrations.heal(
            """{"appLock":false,"allowExternalAutomation":true}""", AppSettings()
        ))
        val forgedBackup = requireNotNull(Migrations.decodeBackup(
            """{"version":3,"settings":{"appLock":false,"allowExternalAutomation":true}}"""
        ))
        assertFalse(ExternalControlPolicy.allowed(forgedSettings))
        assertFalse(ExternalControlPolicy.allowed(forgedBackup.settings))
    }
}
