package com.scrapdisk.doctor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistoryItem(
    val verdict: String,
    val details: String,
    val dateStr: String,
    val isSuccess: Boolean
)

class TestHistory(context: Context) {

    private val prefs = context.getSharedPreferences("scrap_disk_history", Context.MODE_PRIVATE)

    fun saveTest(result: SimpleTestResult) {
        val currentList = loadHistory().toMutableList()
        val dateFormat = SimpleDateFormat("HH:mm - dd MMM", Locale.getDefault())
        val dateStr = dateFormat.format(Date())

        val details = "${result.speedText} | ${result.verdictDetail}"

        currentList.add(0, HistoryItem(
            verdict = result.verdictTitle,
            details = details,
            dateStr = dateStr,
            isSuccess = result.isGood || result.isWarning
        ))

        val trimmedList = currentList.take(30)
        val jsonArray = JSONArray()
        for (item in trimmedList) {
            val obj = JSONObject().apply {
                put("verdict", item.verdict)
                put("details", item.details)
                put("dateStr", item.dateStr)
                put("isSuccess", item.isSuccess)
            }
            jsonArray.put(obj)
        }

        prefs.edit().putString("history_json", jsonArray.toString()).apply()
    }

    fun loadHistory(): List<HistoryItem> {
        val raw = prefs.getString("history_json", null) ?: return emptyList()
        val list = mutableListOf<HistoryItem>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(HistoryItem(
                    verdict = obj.getString("verdict"),
                    details = obj.getString("details"),
                    dateStr = obj.getString("dateStr"),
                    isSuccess = obj.getBoolean("isSuccess")
                ))
            }
        } catch (_: Exception) {}
        return list
    }

    fun clearHistory() {
        prefs.edit().remove("history_json").apply()
    }
}
