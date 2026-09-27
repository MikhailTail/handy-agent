package dev.mikhailtail.handyagent.core.platform

import dev.mikhailtail.handyagent.core.json.Json
import dev.mikhailtail.handyagent.core.model.ReasoningEffort
import dev.mikhailtail.handyagent.core.permission.PermissionMode
import dev.mikhailtail.handyagent.core.provider.ProviderConfig
import dev.mikhailtail.handyagent.core.provider.ProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Loop 8 的平台存储层：KeyValueStore / CredentialStore / ProviderStore / Settings。 */
class PlatformStoreTest {

    // ------------------------------------------------------------ KeyValueStore

    @Test
    fun `missing keys read back as null and are not listed`() {
        val kv = InMemoryKeyValueStore()
        assertNull(kv.getString("nope"))
        assertTrue(kv.keys().isEmpty())
    }

    @Test
    fun `values round trip and can be removed`() {
        val kv = InMemoryKeyValueStore()
        kv.putString("a", "1")
        kv.putString("b", "2")
        assertEquals("1", kv.getString("a"))
        assertEquals(setOf("a", "b"), kv.keys())
        kv.remove("a")
        assertNull(kv.getString("a"))
        assertEquals(setOf("b"), kv.keys())
    }

    @Test
    fun `initial map is loaded`() {
        val kv = InMemoryKeyValueStore(mapOf("k" to "v"))
        assertEquals("v", kv.getString("k"))
    }

    @Test
    fun `prefixed store isolates keys from its neighbours`() {
        val delegate = InMemoryKeyValueStore()
        val a = PrefixedKeyValueStore(delegate, "a.")
        val b = PrefixedKeyValueStore(delegate, "b.")

        a.putString("k", "from-a")
        b.putString("k", "from-b")

        assertEquals("from-a", a.getString("k"))
        assertEquals("from-b", b.getString("k"))
        // 前缀对外不可见：两个 store 都只看到自己的裸 key。
        assertEquals(setOf("k"), a.keys())
        assertEquals(setOf("k"), b.keys())
        // 底层确实存了两份互不覆盖的条目。
        assertEquals(setOf("a.k", "b.k"), delegate.keys())

        a.remove("k")
        assertNull(a.getString("k"))
        assertEquals("from-b", b.getString("k"))
    }

    @Test
    fun `prefixed store rejects an empty prefix`() {
        val e = runCatching { PrefixedKeyValueStore(InMemoryKeyValueStore(), "") }.exceptionOrNull()
        assertNotNull(e)
        assertTrue(e is IllegalArgumentException)
    }

    // --------------------------------------------------------- CredentialStore

    @Test
    fun `in-memory credentials treat an empty key as removal`() {
        val store = InMemoryCredentialStore()
        store.put("anthropic", "sk-1")
        assertTrue(store.has("anthropic"))
        store.put("anthropic", "")
        assertFalse(store.has("anthropic"))
        assertTrue(store.ids().isEmpty())
    }

    @Test
    fun `encrypted credentials round trip`() {
        val kv = InMemoryKeyValueStore()
        val store = KeyValueCredentialStore(kv, FakeSecretCipher())

        store.put("anthropic", "sk-secret-value")
        assertEquals("sk-secret-value", store.get("anthropic"))
        assertEquals(setOf("anthropic"), store.ids())
        assertTrue(store.has("anthropic"))
    }

    @Test
    fun `the api key never lands on disk in plaintext`() {
        val kv = InMemoryKeyValueStore()
        val store = KeyValueCredentialStore(kv, FakeSecretCipher())
        store.put("openai", "sk-live-DEADBEEF")

        val dump = kv.snapshot().values.joinToString("|")
        assertFalse("plaintext key leaked into storage: $dump", dump.contains("DEADBEEF"))
        // 落盘的必须是带版本号的信封，便于将来换算法。
        val envelope = Json.parse(kv.getString("credential.openai")!!)
        assertEquals(KeyValueCredentialStore.VERSION, envelope.int("v"))
        assertNotNull(envelope.str("data"))
    }

    @Test
    fun `the same key encrypts to different ciphertext each time`() {
        val cipher = FakeSecretCipher()
        val once = cipher.encrypt("same")
        val twice = cipher.encrypt("same")
        assertFalse("reused IV/nonce", once == twice)
        assertEquals("same", cipher.decrypt(once))
        assertEquals("same", cipher.decrypt(twice))
    }

    @Test
    fun `a tampered blob decrypts to null instead of throwing`() {
        val kv = InMemoryKeyValueStore()
        val cipher = FakeSecretCipher()
        val store = KeyValueCredentialStore(kv, cipher)
        store.put("anthropic", "sk-abc")

        // 翻转密文正文里的一个字符，模拟数据被改坏。
        val raw = kv.getString("credential.anthropic")!!
        val node = Json.parse(raw)
        val parts = node.str("data")!!.split(".")
        val body = parts[1]
        val flipped = (if (body[0] == '0') '1' else '0') + body.substring(1)
        kv.putString(
            "credential.anthropic",
            Json.obj("v" to Json.Num(1.0), "data" to Json.Str("${parts[0]}.$flipped.${parts[2]}")).encode(),
        )

        assertNull("tampered ciphertext must be treated as 'no credential'", store.get("anthropic"))
        assertFalse(store.has("anthropic"))
        assertTrue("undecryptable entries must not be advertised", store.ids().isEmpty())
    }

    @Test
    fun `credentials of an unknown envelope version are ignored`() {
        val kv = InMemoryKeyValueStore()
        val cipher = FakeSecretCipher()
        val store = KeyValueCredentialStore(kv, cipher)
        kv.putString(
            "credential.anthropic",
            Json.obj("v" to Json.Num(99.0), "data" to Json.Str(cipher.encrypt("sk-old"))).encode(),
        )
        assertNull(store.get("anthropic"))
    }

    @Test
    fun `corrupt credential json does not crash reads`() {
        val kv = InMemoryKeyValueStore(mapOf("credential.x" to "{not json"))
        val store = KeyValueCredentialStore(kv, FakeSecretCipher())
        assertNull(store.get("x"))
        assertTrue(store.ids().isEmpty())
    }

    @Test
    fun `clearing a credential removes its storage entry`() {
        val kv = InMemoryKeyValueStore()
        val store = KeyValueCredentialStore(kv, FakeSecretCipher())
        store.put("anthropic", "sk-1")
        store.put("anthropic", "")
        assertFalse(kv.keys().contains("credential.anthropic"))
    }

    // ------------------------------------------------------------ ProviderStore

    private fun cfg(
        id: String = "anthropic",
        apiKey: String = "",
        models: List<String> = listOf("m1", "m2"),
        headers: Map<String, String> = emptyMap(),
    ) = ProviderConfig(
        id = id,
        name = "Name-$id",
        kind = ProviderKind.ANTHROPIC,
        baseUrl = "https://example.test",
        apiKey = apiKey,
        defaultModel = "m1",
        extraHeaders = headers,
        models = models,
    )

    @Test
    fun `save drops the api key`() {
        val kv = InMemoryKeyValueStore()
        ProviderStore(kv).save(listOf(cfg(apiKey = "sk-must-not-persist")))

        val raw = kv.getString(ProviderStore.KEY)!!
        assertFalse("apiKey must never be persisted", raw.contains("sk-must-not-persist"))
        assertTrue(raw.contains("\"apiKey\"") == false)
    }

    @Test
    fun `configs round trip with fields intact`() {
        val kv = InMemoryKeyValueStore()
        val store = ProviderStore(kv)
        store.save(listOf(cfg(headers = mapOf("X-Title" to "pocket")), cfg(id = "openai")))

        val result = store.load()
        assertTrue(result.errors.isEmpty())
        assertEquals(listOf("anthropic", "openai"), result.configs.map { it.id })
        val first = result.configs.first()
        assertEquals("Name-anthropic", first.name)
        assertEquals(ProviderKind.ANTHROPIC, first.kind)
        assertEquals("https://example.test", first.baseUrl)
        assertEquals("m1", first.defaultModel)
        assertEquals(listOf("m1", "m2"), first.models)
        assertEquals(mapOf("X-Title" to "pocket"), first.extraHeaders)
        assertEquals("apiKey must be empty after load", "", first.apiKey)
    }

    @Test
    fun `corrupt json yields an empty list plus an error`() {
        val kv = InMemoryKeyValueStore(mapOf(ProviderStore.KEY to "{"))
        val result = ProviderStore(kv).load()
        assertTrue(result.configs.isEmpty())
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.first().contains("corrupted"))
    }

    @Test
    fun `a non-array root is rejected without throwing`() {
        val kv = InMemoryKeyValueStore(mapOf(ProviderStore.KEY to """{"id":"x"}"""))
        val result = ProviderStore(kv).load()
        assertTrue(result.configs.isEmpty())
        assertTrue(result.errors.first().contains("array"))
    }

    @Test
    fun `bad entries are skipped but good ones survive`() {
        val kv = InMemoryKeyValueStore(
            mapOf(
                ProviderStore.KEY to """
                    [
                      "not-an-object",
                      {"id":"","kind":"ANTHROPIC"},
                      {"id":"ghost","kind":"NOT_A_KIND"},
                      {"id":"ok","kind":"OPENAI_COMPAT","baseUrl":"https://x","defaultModel":"m"}
                    ]
                """.trimIndent()
            )
        )
        val result = ProviderStore(kv).load()
        assertEquals(listOf("ok"), result.configs.map { it.id })
        assertEquals(3, result.errors.size)
    }

    @Test
    fun `duplicate ids keep the first entry`() {
        val kv = InMemoryKeyValueStore(
            mapOf(
                ProviderStore.KEY to """
                    [
                      {"id":"dup","kind":"ANTHROPIC","name":"first"},
                      {"id":"dup","kind":"ANTHROPIC","name":"second"}
                    ]
                """.trimIndent()
            )
        )
        val result = ProviderStore(kv).load()
        assertEquals(1, result.configs.size)
        assertEquals("first", result.configs.single().name)
    }

    @Test
    fun `merge keeps every preset even when the user store is empty`() {
        val store = ProviderStore(InMemoryKeyValueStore())
        val presets = listOf(cfg(id = "anthropic"), cfg(id = "openai"))
        val merged = store.mergeWithPresets(presets, emptyList())
        assertEquals(listOf("anthropic", "openai"), merged.map { it.id })
    }

    @Test
    fun `merge lets saved values override presets and appends custom ones`() {
        val store = ProviderStore(InMemoryKeyValueStore())
        val presets = listOf(cfg(id = "anthropic", models = listOf("p1")), cfg(id = "openai"))
        val saved = listOf(
            cfg(id = "anthropic", models = listOf("custom-model")),
            cfg(id = "my-gateway"),
        )
        val merged = store.mergeWithPresets(presets, saved)

        assertEquals(listOf("anthropic", "openai", "my-gateway"), merged.map { it.id })
        assertEquals(listOf("custom-model"), merged.first { it.id == "anthropic" }.models)
        // 预置项的名称/地址在用户没改时保留。
        assertEquals("Name-openai", merged.first { it.id == "openai" }.name)
    }

    @Test
    fun `merge keeps preset defaults when the override blanks them out`() {
        val store = ProviderStore(InMemoryKeyValueStore())
        val presets = listOf(cfg(id = "anthropic"))
        val blanked = ProviderConfig(
            id = "anthropic",
            name = "",
            kind = ProviderKind.ANTHROPIC,
            baseUrl = "",
            apiKey = "",
            defaultModel = "",
            models = emptyList(),
        )
        val merged = store.mergeWithPresets(presets, listOf(blanked)).single()
        assertEquals("Name-anthropic", merged.name)
        assertEquals("https://example.test", merged.baseUrl)
        assertEquals("m1", merged.defaultModel)
        assertEquals(listOf("m1", "m2"), merged.models)
    }

    @Test
    fun `withSecrets injects only the credentials that exist`() {
        val credentials = InMemoryCredentialStore().apply { put("anthropic", "sk-a") }
        val store = ProviderStore(InMemoryKeyValueStore())
        val configs = listOf(cfg(id = "anthropic"), cfg(id = "openai"))

        val withKeys = store.withSecrets(configs, credentials)
        assertEquals("sk-a", withKeys.first { it.id == "anthropic" }.apiKey)
        assertEquals("", withKeys.first { it.id == "openai" }.apiKey)
        // 输入列表本身不被修改（避免密钥意外泄漏到调用方持有的对象里）。
        assertTrue(configs.all { it.apiKey.isEmpty() })
    }

    // ---------------------------------------------------------------- Settings

    @Test
    fun `settings fall back to defaults when nothing is stored`() {
        val s = SettingsStore(InMemoryKeyValueStore()).load()
        assertEquals(AppSettings(), s)
        assertEquals(ReasoningEffort.DEFAULT, s.effort)
        assertTrue(s.mcpEnabled)
    }

    @Test
    fun `corrupt settings fall back to defaults instead of throwing`() {
        val store = SettingsStore(InMemoryKeyValueStore(mapOf(SettingsStore.KEY to "not json")))
        assertEquals(AppSettings(), store.load())
    }

    @Test
    fun `settings round trip through json`() {
        val kv = InMemoryKeyValueStore()
        val store = SettingsStore(kv)
        val saved = AppSettings(
            providerId = "deepseek",
            model = "deepseek-reasoner",
            effort = ReasoningEffort.HIGH,
            maxIterations = 40,
            permissionModes = mapOf("write" to PermissionMode.NEVER, "bash" to PermissionMode.ALWAYS),
            mcpEnabled = false,
            compactThreshold = 0.7,
        )
        store.save(saved)
        assertEquals(saved, store.load())
    }

    @Test
    fun `out of range settings are clamped`() {
        val kv = InMemoryKeyValueStore(
            mapOf(
                SettingsStore.KEY to """
                    {"maxIterations": 100000, "compactThreshold": 5.0}
                """.trimIndent()
            )
        )
        val s = SettingsStore(kv).load()
        assertEquals(200, s.maxIterations)
        assertEquals(1.0, s.compactThreshold, 1e-9)
    }

    @Test
    fun `unknown enum labels degrade to defaults`() {
        val kv = InMemoryKeyValueStore(
            mapOf(
                SettingsStore.KEY to """
                    {"effort":"turbo","permissionModes":{"write":"SOMETIMES","read":"ALWAYS"}}
                """.trimIndent()
            )
        )
        val s = SettingsStore(kv).load()
        assertEquals(ReasoningEffort.DEFAULT, s.effort)
        // 坏条目被忽略，好条目仍然生效。
        assertEquals(mapOf("read" to PermissionMode.ALWAYS), s.permissionModes)
    }

    @Test
    fun `update performs a read modify write`() {
        val store = SettingsStore(InMemoryKeyValueStore())
        store.update { it.copy(providerId = "anthropic") }
        val next = store.update { it.copy(effort = ReasoningEffort.LOW) }

        assertEquals("anthropic", next.providerId)
        assertEquals(ReasoningEffort.LOW, next.effort)
        assertEquals("anthropic", store.load().providerId)
    }

    @Test
    fun `resolvedModel prefers the explicit choice`() {
        assertEquals("chosen", AppSettings(model = "chosen").resolvedModel("fallback"))
        assertEquals("fallback", AppSettings(model = "  ").resolvedModel("fallback"))
    }
}
