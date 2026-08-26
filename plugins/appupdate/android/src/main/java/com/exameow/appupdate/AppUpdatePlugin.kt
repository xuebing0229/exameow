package com.exameow.appupdate

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Channel
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private const val LATEST_RELEASE_API =
  "https://api.github.com/repos/xuebing0229/exameow/releases/latest"

@InvokeArg
class DownloadArgs {
  lateinit var url: String
  lateinit var events: Channel
}

@TauriPlugin
class AppUpdatePlugin(private val activity: Activity) : Plugin(activity) {
  private val executor = Executors.newSingleThreadExecutor()

  @Command
  fun checkLatest(invoke: Invoke) {
    executor.execute {
      try {
        val release = readJson(LATEST_RELEASE_API)
        val version = release.getString("tag_name").removePrefix("v")
        val assets = release.getJSONArray("assets")
        var apkUrl = ""
        for (i in 0 until assets.length()) {
          val asset = assets.getJSONObject(i)
          val name = asset.optString("name")
          if (name.startsWith("Exameow-v") && name.endsWith("-android_arm64.apk")) {
            apkUrl = asset.getString("browser_download_url")
            break
          }
        }
        if (apkUrl.isEmpty()) throw IllegalStateException("release_apk_not_found")

        val result = JSObject()
        result.put("version", version)
        result.put("url", apkUrl)
        activity.runOnUiThread { invoke.resolve(result) }
      } catch (e: Exception) {
        activity.runOnUiThread { invoke.reject("latest_release_fetch_failed: ${e.message}") }
      }
    }
  }

  @Command
  fun downloadAndInstall(invoke: Invoke) {
    val args = invoke.parseArgs(DownloadArgs::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
      !activity.packageManager.canRequestPackageInstalls()
    ) {
      val intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${activity.packageName}")
      )
      activity.startActivity(intent)
      invoke.reject("install_permission_required")
      return
    }

    executor.execute {
      try {
        val updateDir = File(activity.cacheDir, "updates").apply { mkdirs() }
        val apk = File(updateDir, "Exameow-update.apk")
        download(args.url, apk, args.events)

        val uri = FileProvider.getUriForFile(
          activity,
          "${activity.packageName}.appupdate.fileprovider",
          apk,
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
          setDataAndType(uri, "application/vnd.android.package-archive")
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.runOnUiThread {
          activity.startActivity(installIntent)
          val result = JSObject()
          result.put("status", "installing")
          invoke.resolve(result)
        }
      } catch (e: Exception) {
        activity.runOnUiThread { invoke.reject("apk_download_failed: ${e.message}") }
      }
    }
  }

  private fun readJson(url: String): JSONObject {
    val connection = open(url)
    try {
      if (connection.responseCode !in 200..299) {
        throw IllegalStateException("HTTP ${connection.responseCode}")
      }
      return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    } finally {
      connection.disconnect()
    }
  }

  private fun download(url: String, target: File, events: Channel) {
    val connection = open(url)
    try {
      if (connection.responseCode !in 200..299) {
        throw IllegalStateException("HTTP ${connection.responseCode}")
      }
      val total = connection.contentLengthLong
      var downloaded = 0L
      var lastProgress = -1
      connection.inputStream.use { input ->
        FileOutputStream(target, false).use { output ->
          val buffer = ByteArray(64 * 1024)
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            downloaded += count
            if (total > 0) {
              val progress = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
              if (progress != lastProgress) {
                lastProgress = progress
                val event = JSObject()
                event.put("progress", progress)
                events.send(event)
              }
            }
          }
        }
      }
      if (downloaded == 0L || (total > 0 && downloaded != total)) {
        target.delete()
        throw IllegalStateException("incomplete_download")
      }
    } finally {
      connection.disconnect()
    }
  }

  private fun open(url: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
      instanceFollowRedirects = true
      connectTimeout = 15_000
      readTimeout = 120_000
      setRequestProperty("Accept", "application/vnd.github+json")
      setRequestProperty("User-Agent", "Exameow-Android-Updater")
    }
}
