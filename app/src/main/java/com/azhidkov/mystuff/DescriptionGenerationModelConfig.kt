package com.azhidkov.mystuff

import com.google.android.gms.tasks.Tasks
import com.google.firebase.Firebase
import com.google.firebase.remoteconfig.remoteConfig

internal data class FetchedDescriptionGenerationModel(
    val name: String?,
    val fetchedAtMillis: Long,
)

internal fun interface DescriptionGenerationModelConfig {
    fun modelName(): String
}

internal class RemoteConfigDescriptionGenerationModelConfig(
    private val source: () -> FetchedDescriptionGenerationModel =
        ::fetchDescriptionGenerationModel,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val maxAgeMillis: Long = REMOTE_MODEL_MAX_AGE_MILLIS,
) : DescriptionGenerationModelConfig {
    override fun modelName(): String {
        val fetched = try {
            source()
        } catch (_: Exception) {
            return BUNDLED_DESCRIPTION_GENERATION_MODEL
        }
        if (nowMillis() - fetched.fetchedAtMillis > maxAgeMillis) {
            return BUNDLED_DESCRIPTION_GENERATION_MODEL
        }
        return fetched.name
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: BUNDLED_DESCRIPTION_GENERATION_MODEL
    }
}

internal const val BUNDLED_DESCRIPTION_GENERATION_MODEL = "gemini-3.7-flash"

private fun fetchDescriptionGenerationModel(): FetchedDescriptionGenerationModel {
    val remoteConfig = Firebase.remoteConfig
    Tasks.await(remoteConfig.fetchAndActivate())
    return FetchedDescriptionGenerationModel(
        name = remoteConfig.getAll()[DESCRIPTION_GENERATION_MODEL_PARAMETER]?.asString(),
        fetchedAtMillis = remoteConfig.info.fetchTimeMillis,
    )
}

private const val DESCRIPTION_GENERATION_MODEL_PARAMETER = "description_generation_model"
private const val REMOTE_MODEL_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L
