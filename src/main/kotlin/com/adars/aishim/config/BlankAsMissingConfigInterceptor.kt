package com.adars.aishim.config

import io.smallrye.config.ConfigSourceInterceptor
import io.smallrye.config.ConfigSourceInterceptorContext
import io.smallrye.config.ConfigValue
import io.smallrye.config.Priorities
import jakarta.annotation.Priority

/**
 * Treats blank config values as missing so that `${VAR:default}` expressions fall back
 * when the env var is exported empty (e.g. `OPENAI_API_KEY=` in a .env file).
 *
 * Why: SmallRye's `:default` only fires when the property is absent. An empty string
 * counts as "set", which then trips converters that reject empty (e.g. langchain4j api-key).
 *
 * How to apply: registered via META-INF/services so SmallRye picks it up at config build.
 */
@Priority(Priorities.LIBRARY + 200)
class BlankAsMissingConfigInterceptor : ConfigSourceInterceptor {
    override fun getValue(context: ConfigSourceInterceptorContext, name: String): ConfigValue? {
        val value = context.proceed(name) ?: return null
        return if (value.value.isNullOrBlank()) null else value
    }
}
