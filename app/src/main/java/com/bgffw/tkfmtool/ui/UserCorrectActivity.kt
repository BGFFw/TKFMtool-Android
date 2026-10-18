package com.bgffw.tkfmtool.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bgffw.tkfmtool.R
import com.bgffw.tkfmtool.core.Correction
import com.bgffw.tkfmtool.core.SettingsRepository
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * 识图结果纠错页。
 *
 * 对应原版 `UserCorrectWindow.js`：
 * 列表展示所有纠正项，点击切换启用，长按删除，右下角新增。
 * 数据仍然存在同一个 `chosed` 仓库的 `UserCorrectSet` 键下。
 */
class UserCorrectActivity : AppCompatActivity() {

    private lateinit var settings: SettingsRepository
    private val items = ArrayList<Correction>()
    private lateinit var adapter: CorrectionAdapter
    private lateinit var recycler: RecyclerView

    private val materialColors = listOf(
        "#e91e63", "#ab47bc", "#5c6bc0", "#7e57c2", "#2196f3", "#00bcd4",
        "#26a69a", "#4caf50", "#8bc34a", "#ffeb3b", "#ffa726", "#78909c", "#8d6e63"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_correct)

        settings = SettingsRepository(this)
        items.clear()
        items.addAll(settings.corrections)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        recycler = findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = CorrectionAdapter(
            items = items,
            onChanged = { persist() },
            onRequestDelete = { position -> confirmDelete(position) }
        )
        recycler.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener { promptAdd() }
    }

    override fun onPause() {
        super.onPause()
        persist()
    }

    private fun persist() {
        settings.corrections = items.toList()
    }

    private fun confirmDelete(position: Int) {
        val item = items.getOrNull(position) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.correct_delete_title)
            .setMessage(getString(R.string.correct_delete_message, item.title))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                adapter.removeAt(position)
                persist()
            }
            .show()
    }

    private fun promptAdd() {
        val input = buildInput(getString(R.string.correct_input_from))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.correct_add)
            .setView(wrap(input))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val from = input.text?.toString()?.trim().orEmpty()
                if (from.isNotEmpty()) promptAddSummary(from)
            }
            .show()
    }

    private fun promptAddSummary(from: String) {
        val input = buildInput(getString(R.string.correct_input_to))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.correct_add)
            .setView(wrap(input))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val to = input.text?.toString()?.trim().orEmpty()
                if (to.isEmpty()) {
                    Toast.makeText(this, R.string.correct_save_failed, Toast.LENGTH_SHORT).show()
                } else {
                    adapter.add(Correction(from, to, materialColors.random(), true))
                    persist()
                    recycler.scrollToPosition(items.size - 1)
                }
            }
            .show()
    }

    private fun buildInput(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        setSingleLine(true)
    }

    private fun wrap(view: View): View {
        val density = resources.displayMetrics.density
        val horizontal = (16 * density).toInt()
        val vertical = (8 * density).toInt()

        return FrameLayout(this).apply {
            setPadding(horizontal, vertical, horizontal, 0)
            addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }
}
