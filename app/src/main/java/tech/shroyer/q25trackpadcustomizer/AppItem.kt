package tech.shroyer.q25trackpadcustomizer

import android.graphics.drawable.Drawable

// Represent a launchable app in the list
data class AppItem(
    val packageName: String,
    val label: String,
    val icon: Drawable
)