package com.bgffw.tkfmtool.ui

import android.graphics.Color
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bgffw.tkfmtool.R
import com.bgffw.tkfmtool.core.Correction

/**
 * 纠错项列表。对应原版 `UserCorrectWindow.js` 的 `todoList`：
 * 色条 + 原文（勾选后加删除线）+ 改正后文本 + 勾选框。
 */
class CorrectionAdapter(
    private val items: MutableList<Correction>,
    private val onChanged: () -> Unit,
    private val onRequestDelete: (Int) -> Unit
) : RecyclerView.Adapter<CorrectionAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val colorBar: View = view.findViewById(R.id.colorBar)
        val title: TextView = view.findViewById(R.id.tvTitle)
        val summary: TextView = view.findViewById(R.id.tvSummary)
        val done: CheckBox = view.findViewById(R.id.cbDone)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_correction, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]

        holder.colorBar.setBackgroundColor(parseColor(item.color))
        holder.title.text = item.title
        holder.summary.text = item.summary
        holder.done.isChecked = item.done
        applyStrikeThrough(holder.title, item.done)

        holder.itemView.setOnClickListener {
            val index = holder.bindingAdapterPosition
            if (index == RecyclerView.NO_POSITION) return@setOnClickListener
            val current = items[index]
            items[index] = current.copy(done = !current.done)
            notifyItemChanged(index)
            onChanged()
        }

        holder.itemView.setOnLongClickListener {
            val index = holder.bindingAdapterPosition
            if (index != RecyclerView.NO_POSITION) onRequestDelete(index)
            true
        }
    }

    fun add(correction: Correction) {
        items.add(correction)
        notifyItemInserted(items.size - 1)
    }

    fun removeAt(position: Int) {
        if (position !in items.indices) return
        items.removeAt(position)
        notifyItemRemoved(position)
        if (position < items.size) {
            notifyItemRangeChanged(position, items.size - position)
        }
    }

    private fun applyStrikeThrough(view: TextView, struck: Boolean) {
        val flags = view.paintFlags
        view.paintFlags = if (struck) {
            flags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            flags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
    }

    private fun parseColor(raw: String): Int = try {
        Color.parseColor(raw)
    } catch (t: Throwable) {
        Color.parseColor("#2196F3")
    }
}
