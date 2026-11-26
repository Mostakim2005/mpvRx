package app.gyrolet.mpvrx.preferences

import app.gyrolet.mpvrx.preferences.preference.PreferenceStore

class BookmarkSyncPreferences(
  preferenceStore: PreferenceStore,
) {
  val hubUrl = preferenceStore.getString("bookmark_sync_hub_url", "")
  val syncToken = preferenceStore.getString("bookmark_sync_token", "")
  val autoSyncEnabled = preferenceStore.getBoolean("bookmark_sync_auto_enabled", false)
  val usbModeEnabled = preferenceStore.getBoolean("bookmark_sync_usb_mode", false)
  val lastSyncIso = preferenceStore.getString("bookmark_sync_last_iso", "")
  val lastSyncSummary = preferenceStore.getString("bookmark_sync_last_summary", "")

  companion object {
    fun normalizeHubUrl(raw: String): String {
      val trimmed = raw.trim().trimEnd('/')
      if (trimmed.isEmpty()) return ""
      return if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
      ) {
        trimmed
      } else {
        "http://$trimmed"
      }
    }
  }
}
