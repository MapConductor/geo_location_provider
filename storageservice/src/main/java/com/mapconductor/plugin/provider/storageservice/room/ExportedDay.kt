package com.mapconductor.plugin.provider.storageservice.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "exported_days")
data class ExportedDay(
    /** Days since UTC 1970-01-01 (same base as LocalDate.toEpochDay). */
    @PrimaryKey val epochDay: Long,
    /** Whether the local ZIP has been generated. */
    val exportedLocal: Boolean = false,
    /** Whether upload to Drive has completed. */
    val uploaded: Boolean = false,
    /** Drive file id recorded for this day, if any. */
    val driveFileId: String? = null,
    /** Last error message, or null when there is no error. */
    val lastError: String? = null
)

