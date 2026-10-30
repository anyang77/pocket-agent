package dev.pocketagent.ui

import dev.pocketagent.data.AiSettings

/** A late model-list response must never overwrite a newer edit or another profile. */
internal fun modelCatalogRequestMatches(
    requestRevision: Long,
    currentRevision: Long,
    requestProfile: String,
    currentProfile: String,
    requested: AiSettings,
    current: AiSettings,
): Boolean = requestRevision == currentRevision && requestProfile == currentProfile && requested == current
