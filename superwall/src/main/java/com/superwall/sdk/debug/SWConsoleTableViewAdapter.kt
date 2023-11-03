package com.superwall.sdk.debug

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TableViewAdapter(private var data: List<Pair<String, String>>) : RecyclerView.Adapter<TableViewAdapter.ViewHolder>() {
    fun updateData(newData: Map<String, String>) {
        data = newData
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (key, value) = data[position]
        holder.text1.text = value
        holder.text2.text = "{{ $key }}"
    }

    override fun getItemCount() = data.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        var text1: TextView = view.findViewById(android.R.id.text1)
        var text2: TextView = view.findViewById(android.R.id.text2)
    }
}
