package dev.mikhailtail.handyagent.core.provider

import dev.mikhailtail.handyagent.core.provider.anthropic.AnthropicProvider
import dev.mikhailtail.handyagent.core.provider.openai.OpenAiCompatProvider

object ProviderFactory {

    fun create(
        config: ProviderConfig,
        http: HttpEngine,
        images: ImageResolver = ImageResolver.NONE,
    ): LlmProvider = when (config.kind) {
        ProviderKind.ANTHROPIC -> AnthropicProvider(config, http, images = images)
        ProviderKind.OPENAI_COMPAT -> OpenAiCompatProvider(config, http, images = images)
    }

    /** 设置页的预置项（apiKey 留空，由用户填写）。 */
    fun presets(): List<ProviderConfig> = listOf(
        ProviderConfig(
            id = "anthropic",
            name = "Anthropic",
            kind = ProviderKind.ANTHROPIC,
            baseUrl = "https://api.anthropic.com",
            apiKey = "",
            defaultModel = "claude-sonnet-4-5",
            models = listOf("claude-sonnet-4-5", "claude-opus-4-1", "claude-haiku-4-5"),
        ),
        ProviderConfig(
            id = "openai",
            name = "OpenAI",
            kind = ProviderKind.OPENAI_COMPAT,
            baseUrl = "https://api.openai.com/v1",
            apiKey = "",
            defaultModel = "gpt-4o",
            models = listOf("gpt-4o", "gpt-4o-mini", "o4-mini"),
        ),
        ProviderConfig(
            id = "deepseek",
            name = "DeepSeek",
            kind = ProviderKind.OPENAI_COMPAT,
            baseUrl = "https://api.deepseek.com/v1",
            apiKey = "",
            defaultModel = "deepseek-chat",
            models = listOf("deepseek-chat", "deepseek-reasoner"),
        ),
        ProviderConfig(
            id = "ollama",
            name = "Ollama (本地)",
            kind = ProviderKind.OPENAI_COMPAT,
            baseUrl = "http://127.0.0.1:11434/v1",
            apiKey = "",
            defaultModel = "qwen2.5:7b",
        ),
    )
}
