package com.notessync.app.sync

import android.app.Application
import android.content.Context
import android.util.Base64
import com.notessync.app.sync.trust.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class TrustStoreTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(AndroidTrustedPeers.PREFS, Context.MODE_PRIVATE)
    private class Keys : TrustKeyProvider {
        var current: SecretKey? = null; var created = 0
        override fun key(create: Boolean): SecretKey {
            current?.let { return it }; check(create)
            created++; return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { current = it }
        }
        override fun clear() { current = null }
    }
    @Before fun before() { prefs.edit().clear().commit() }
    @After fun after() { prefs.edit().clear().commit() }
    @Test fun `empty startup avoids key creation and persisted peers contain only encrypted authenticated bytes`() = runBlocking {
        val keys = Keys(); val store = AndroidTrustedPeers(app, keys); store.load()
        assertTrue(store.state.value.ready && store.state.value.available); assertEquals(0, keys.created)
        val id = UUID.randomUUID().toString(); val secret = "ab".repeat(32); val name = "Private device %1" + '$' + "s"
        store.pair(id, name, secret, "192.168.1.3", 100)
        val raw = prefs.getString("encrypted", null)!!
        assertEquals(setOf("encrypted"), prefs.all.keys); assertFalse(raw.contains(id)); assertFalse(raw.contains(secret)); assertFalse(raw.contains(name))
        assertFalse(store.state.value.toString().contains(secret)); assertFalse(store.get(id).toString().contains(secret))
        val reloaded = AndroidTrustedPeers(app, keys); reloaded.load(); assertEquals(secret, reloaded.get(id)!!.secret)
        assertEquals(name, reloaded.state.value.peers.single().name)
        store.synced(id, store.state.value.peers.single().revision, 120, "192.168.1.4")
        assertNotEquals(raw, prefs.getString("encrypted", null)); assertEquals(120, store.get(id)!!.lastSyncAt)
    }
    @Test fun `tampering missing device key and corruption fail closed with explicit reset as recovery`() = runBlocking {
        val keys = Keys(); val store = AndroidTrustedPeers(app, keys); store.load(); val id = UUID.randomUUID().toString()
        store.pair(id, "Device", "ab".repeat(32), "192.168.1.3", 100)
        val original = prefs.getString("encrypted", null)!!
        val bytes = Base64.decode(original, Base64.NO_WRAP).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        prefs.edit().putString("encrypted", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
        val bad = AndroidTrustedPeers(app, keys); bad.load(); assertFalse(bad.state.value.available); assertTrue(bad.state.value.peers.isEmpty())
        assertTrue(runCatching { bad.get(id) }.isFailure)
        prefs.edit().putString("encrypted", original).commit(); keys.current = null
        val missing = AndroidTrustedPeers(app, keys); missing.load(); assertFalse(missing.state.value.available); assertEquals(1, keys.created)
        missing.reset(); assertTrue(missing.state.value.available); assertTrue(prefs.all.isEmpty())
        missing.pair(id, "Again", "cd".repeat(32), "192.168.1.3", 0); assertEquals(2, keys.created)
    }
    @Test fun `re-pairing revokes previous revision and stale success cannot restore forgotten trust`() = runBlocking {
        val store = AndroidTrustedPeers(app, Keys()); store.load(); val id = UUID.randomUUID().toString()
        store.pair(id, "One", "ab".repeat(32), "192.168.1.3", 100); val old = store.state.value.peers.single().revision
        store.pair(id, "Two", "cd".repeat(32), "192.168.1.4", 200); val latest = store.state.value.peers.single().revision
        assertNotEquals(old, latest); assertFalse(store.current(id, old)); assertTrue(store.current(id, latest))
        store.synced(id, old, 900, "192.168.1.9"); assertEquals(200, store.get(id)!!.lastSyncAt)
        store.forget(id); store.synced(id, latest, 1_000, "192.168.1.9")
        assertNull(store.get(id)); assertFalse(store.current(id, latest)); assertTrue(prefs.all.isEmpty())
    }
    @Test fun `capacity rejection preserves existing usable peers and keystore spec has independent purpose`() = runBlocking {
        val store = AndroidTrustedPeers(app, Keys()); store.load()
        repeat(32) { store.pair(UUID.randomUUID().toString(), "Device $it", "ab".repeat(32), "192.168.1.3", 0) }
        val kept = store.state.value.peers.first()
        assertTrue(runCatching { store.pair(UUID.randomUUID().toString(), "Extra", "cd".repeat(32), "192.168.1.4", 0) }.isFailure)
        assertTrue(store.state.value.available); assertEquals(32, store.state.value.peers.size); assertNotNull(store.get(kept.id))
        val spec = AndroidTrustedPeers.spec()
        assertEquals("notels.sync.trust.v1", spec.keystoreAlias); assertEquals(256, spec.keySize)
        assertTrue(spec.isRandomizedEncryptionRequired); assertFalse(spec.isUserAuthenticationRequired)
    }
}
