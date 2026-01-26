package tech.shroyer.q25trackpadcustomizer

import android.graphics.drawable.Drawable

// Represent an app in the current exclusion list
data class ExcludedAppItem(
    val packageName: String,
    val label: String,
    val icon: Drawable?
)