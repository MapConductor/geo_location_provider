package com.mapconductor.plugin.provider.geolocation.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mapconductor.plugin.provider.geolocation.ui.main.ManualExportViewModel
import com.mapconductor.plugin.provider.geolocation.ui.main.TodayPreviewMode
import com.mapconductor.plugin.provider.geolocation.work.MidnightExportWorker

/**
 * バックアップ操作セクション（最終仕様）
 *
 * - 「前日以前をBackup」：今日より前を“日付ごと”にすべてアップロード（成功: ZIP削除+Room削除 / 失敗: ZIP削除・Room保持）
 * - 「今日のPreview」：今日0:00〜現在を1ファイルにバックアップ。アップロードする/しないをユーザ選択（Roomは常に保持）
 *
 * 旧「Run now」「Run backlog (schedule)」は作りません。
 */
@Composable
fun BackupSection(
    modifier: Modifier = Modifier,
    manualExportViewModel: ManualExportViewModel = viewModel(factory = ManualExportViewModel.factory(LocalContext.current))
) {
    val context = LocalContext.current

    var showTodayDialog by remember { mutableStateOf(false) }

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "バックアップ",
                style = MaterialTheme.typography.titleMedium
            )

            // A) 前日以前をBackup（＝バックログ即時実行）
            Button(
                onClick = { runBacklogNow(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("前日以前をBackup")
            }
            Text(
                text = "今日より前のデータを日付ごとにバックアップしてアップロードします（今日分は含みません）。",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(12.dp))

            // B) 今日のPreview（ダイアログで「アップロードする/しない」を選択）
            OutlinedButton(
                onClick = { showTodayDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("今日のPreview")
            }
            Text(
                text = "今日0:00〜現在のデータを1ファイルにバックアップします。アップロードする/しないを選択できます（Roomは削除しません）。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    if (showTodayDialog) {
        TodayPreviewChoiceDialog(
            onUpload = {
                manualExportViewModel.backupToday(TodayPreviewMode.UPLOAD_AND_DELETE_LOCAL)
                showTodayDialog = false
            },
            onSaveOnly = {
                manualExportViewModel.backupToday(TodayPreviewMode.SAVE_TO_DOWNLOADS_ONLY)
                showTodayDialog = false
            },
            onDismiss = { showTodayDialog = false }
        )
    }
}

/** 「前日以前をBackup」 = MidnightExportWorker の即時実行 */
private fun runBacklogNow(context: Context) {
    MidnightExportWorker.runBacklogNow(context)
}

/** 今日のPreview：アップロードする/しない の選択ダイアログ */
@Composable
private fun TodayPreviewChoiceDialog(
    onUpload: () -> Unit,
    onSaveOnly: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("今日のバックアップ") },
        text = {
            Text(
                "今日0:00〜現在のデータを1ファイルにまとめます。\n" +
                        "・アップロードする：成功/失敗に関わらずZIPは削除（Roomは保持）\n" +
                        "・アップロードしない：Downloadsに保存（ZIP保持、Roomは保持）"
            )
        },
        confirmButton = {
            TextButton(onClick = onUpload) { Text("アップロードする") }
        },
        dismissButton = {
            TextButton(onClick = onSaveOnly) { Text("保存のみ") }
        }
    )
}
