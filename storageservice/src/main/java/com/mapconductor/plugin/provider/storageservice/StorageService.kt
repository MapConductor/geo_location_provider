package com.mapconductor.plugin.provider.storageservice

import android.content.Context
import android.util.Log
import com.mapconductor.plugin.provider.storageservice.room.AppDatabase
import com.mapconductor.plugin.provider.storageservice.room.ExportedDay
import com.mapconductor.plugin.provider.storageservice.room.LocationSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Thin facade that acts as the single entry point to Room (AppDatabase).
 *
 * Responsibilities:
 * - Only this object should touch AppDatabase and DAO types from outside the storageservice module.
 * - All operations on LocationSample and ExportedDay go through this object.
 *
 * Typical callers (as of 2025-11):
 * - GeoLocationService and other producers call insertLocation.
 * - History screens (for example HistoryViewModel) observe latestFlow.
 * - MidnightExportWorker uses getLocationsBetween, deleteLocations and the ExportedDay helpers.
 * - DriveSettingsScreen and Pickup use getAllLocations / getLocationsBetween through higher level use cases.
 *
 * Callers should depend on the contracts described here (ordering, ranges, error handling)
 * instead of relying on DAO implementation details.
 */
object StorageService {

    // ------------------------------------------------------------------------
    // LocationSample API
    // ------------------------------------------------------------------------

    /**
     * Flow of the latest [limit] LocationSample rows ordered newest first.
     *
     * Intended for:
     * - history and home screens that need to follow new samples in near real time.
     *
     * Contract:
     * - The list is ordered descending by timeMillis (newest first).
     * - [limit] must be greater than or equal to 1 and is validated by the caller.
     */
    fun latestFlow(ctx: Context, limit: Int): Flow<List<LocationSample>> =
        AppDatabase.get(ctx).locationSampleDao().latestFlow(limit)

    /**
     * Returns all LocationSample rows ordered ascending by timeMillis.
     *
     * Intended for:
     * - one shot exports and previews where the total number of rows is relatively small.
     *
     * Contract:
     * - The result list is sorted ascending by timeMillis.
     * - For large datasets prefer [getLocationsBetween] to avoid high memory usage.
     * - This is a suspending call and should be invoked from a coroutine context.
     */
    suspend fun getAllLocations(ctx: Context): List<LocationSample> =
        withContext(Dispatchers.IO) {
            AppDatabase.get(ctx).locationSampleDao().findAll()
        }

    /**
     * Returns LocationSample rows in the half open interval [from, to) ordered ascending by timeMillis.
     *
     * Intended for:
     * - daily export such as MidnightExportWorker.
     * - time range based extraction for Pickup / dataselector.
     *
     * Contract:
     * - The time range is [from, to) (from inclusive, to exclusive).
     * - The result list is sorted ascending by timeMillis.
     * - [softLimit] is a safety upper bound that may be enforced at the DAO level
     *   to avoid loading an unexpectedly huge number of rows.
     * - This is a suspending call and should be invoked from a coroutine context.
     */
    suspend fun getLocationsBetween(
        ctx: Context,
        from: Long,
        to: Long,
        softLimit: Int = 1_000_000
    ): List<LocationSample> =
        withContext(Dispatchers.IO) {
            AppDatabase.get(ctx).locationSampleDao()
                .getInRangeAscOnce(from = from, to = to, softLimit = softLimit)
        }

    /**
     * Inserts a single LocationSample and returns the new row id.
     *
     * Contract:
     * - Logs the count before and after insertion and the provider/timeMillis
     *   with the tag "DB/TRACE" for debugging.
     * - If an exception occurs it is propagated to the caller.
     * - This is a suspending call and should be invoked from a coroutine context.
     */
    suspend fun insertLocation(ctx: Context, sample: LocationSample): Long =
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.get(ctx).locationSampleDao()
            val before = dao.countAll()
            Log.d(
                "DB/TRACE",
                "count-before=$before provider=${sample.provider} t=${sample.timeMillis}"
            )

            val rowId = dao.insert(sample)

            val after = dao.countAll()
            Log.d("DB/TRACE", "count-after=$after rowId=$rowId")

            rowId
        }

    /**
     * Deletes the given LocationSample rows in a batch.
     *
     * Contract:
     * - If [items] is empty this method does nothing and avoids hitting the database.
     * - If an exception occurs it is propagated to the caller; workers may choose to catch it.
     * - This is a suspending call and should be invoked from a coroutine context.
     */
    suspend fun deleteLocations(ctx: Context, items: List<LocationSample>) =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext
            val dao = AppDatabase.get(ctx).locationSampleDao()
            dao.deleteAll(items)
        }

    /**
     * Returns the earliest timeMillis among all LocationSample rows, or null when empty.
     *
     * Intended for:
     * - determining the first day that may need backup/export processing.
     */
    suspend fun firstSampleTimeMillis(ctx: Context): Long? =
        withContext(Dispatchers.IO) {
            AppDatabase.get(ctx).locationSampleDao().minTimeMillis()
        }

    /**
     * Returns the latest timeMillis among all LocationSample rows, or null when empty.
     *
     * Intended for:
     * - determining the last day that may need backup/export processing.
     */
    suspend fun lastSampleTimeMillis(ctx: Context): Long? =
        withContext(Dispatchers.IO) {
            AppDatabase.get(ctx).locationSampleDao().maxTimeMillis()
        }

    // ------------------------------------------------------------------------
    // ExportedDay API
    // ------------------------------------------------------------------------

    /**
     * Ensures that an ExportedDay row for [epochDay] exists, creating it if necessary.
     *
     * Intended for:
     * - initial seeding performed by MidnightExportWorker when it prepares the backlog.
     *
     * Contract:
     * - [epochDay] uses the same base as LocalDate.toEpochDay (days since UTC 1970-01-01).
     * - If there is already a row for the same epochDay its contents are left unchanged.
     * - This is a suspending call and should be invoked from a coroutine context.
     */
    suspend fun ensureExportedDay(ctx: Context, epochDay: Long) =
        withContext(Dispatchers.IO) {
            val dayDao = AppDatabase.get(ctx).exportedDayDao()
            dayDao.ensure(ExportedDay(epochDay = epochDay))
        }

    /**
     * Returns the oldest ExportedDay that is not yet marked as uploaded, or null if none.
     *
     * Intended for:
     * - backlog processing loops in MidnightExportWorker when choosing the next day to handle.
     *
     * Contract:
     * - If there is no matching row, null is returned.
     * - The exact definition of "not uploaded yet" is delegated to the ExportedDayDao implementation.
     * - This is a suspending call and should be invoked from a coroutine context.
     */
    suspend fun oldestNotUploadedDay(ctx: Context): ExportedDay? =
        withContext(Dispatchers.IO) {
            AppDatabase.get(ctx).exportedDayDao().oldestNotUploaded()
        }

    /**
     * Returns the total number of ExportedDay rows.
     *
     * Intended for:
     * - diagnostic and UI usage such as Drive settings backup summary.
     *
     * Contract:
     * - Returns 0 when the table is empty.
     * - This is a suspending call and should be invoked from a coroutine context.
     */
    suspend fun exportedDayCount(ctx: Context): Long =
        withContext(Dispatchers.IO) {
            AppDatabase.get(ctx).exportedDayDao().countAll()
        }

    /**
     * Returns the next ExportedDay with uploaded == false and epochDay strictly greater
     * than [afterEpochDay], or null when there is no such day.
     *
     * Intended for:
     * - backlog iteration in MidnightExportWorker so that one worker run processes
     *   each day at most once even when upload is skipped or fails.
     *
     * Contract:
     * - If there is no matching row, null is returned.
     * - This is a suspending call and should be invoked from a coroutine context.
     */
    suspend fun nextNotUploadedDayAfter(ctx: Context, afterEpochDay: Long): ExportedDay? =
        withContext(Dispatchers.IO) {
            AppDatabase.get(ctx).exportedDayDao().nextNotUploadedAfter(afterEpochDay)
        }

    /**
     * Marks [epochDay] as exported locally (GeoJSON and ZIP file generated).
     *
     * Intended for:
     * - calling immediately after local export succeeds, regardless of upload result.
     *
     * Contract:
     * - If the day does not exist the behavior is defined by ExportedDayDao.
     * - This is a suspending call and should be invoked from a coroutine context.
     */
    suspend fun markExportedLocal(ctx: Context, epochDay: Long) =
        withContext(Dispatchers.IO) {
            AppDatabase.get(ctx).exportedDayDao().markExportedLocal(epochDay)
        }

    /**
     * Marks [epochDay] as uploaded and records the Drive file id if available.
     *
     * Contract:
     * - [fileId] may be null to keep the API simple; callers can pass null if they do not need it.
     * - This is a suspending call and should be invoked from a coroutine context.
     */
    suspend fun markUploaded(ctx: Context, epochDay: Long, fileId: String?) =
        withContext(Dispatchers.IO) {
            AppDatabase.get(ctx).exportedDayDao().markUploaded(epochDay, fileId)
        }

    /**
     * Records an error message [msg] for [epochDay].
     *
     * Intended for:
     * - failures during ZIP generation or Drive upload so that UI and logs can explain what went wrong.
     *
     * Contract:
     * - [msg] is expected to be a human readable message, for example an HTTP status and summary.
     * - This is a suspending call and should be invoked from a coroutine context.
     */
    suspend fun markExportError(ctx: Context, epochDay: Long, msg: String) =
        withContext(Dispatchers.IO) {
            AppDatabase.get(ctx).exportedDayDao().markError(epochDay, msg)
        }
}
