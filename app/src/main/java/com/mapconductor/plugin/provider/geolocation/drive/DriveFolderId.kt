package com.mapconductor.plugin.provider.geolocation.drive

object DriveFolderId {
    /**
     * 例:
     * https://drive.google.com/drive/folders/<ID>?usp=drive_link
     * https://drive.google.com/drive/u/0/folders/<ID>
     */
    fun extractFromUrlOrId(input: String?): String? {
        if (input.isNullOrBlank()) return null
        // すでにIDだけの場合を許容
        if (!input.contains("http")) return input.trim()

        val regex = Regex("""/folders/([a-zA-Z0-9_-]+)""")
        val m = regex.find(input) ?: return null
        return m.groupValues.getOrNull(1)
    }
}
