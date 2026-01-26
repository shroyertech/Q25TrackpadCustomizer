package tech.shroyer.q25trackpadcustomizer

import android.R as AndroidR
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ExcludedAppsAdapter(
    private val items: MutableList<ExcludedAppItem>,
    private val onRemove: (ExcludedAppItem) -> Unit
) : RecyclerView.Adapter<ExcludedAppsAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_excluded_app, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvLabel.text = item.label
        holder.tvPackage.text = item.packageName
        if (item.icon != null) {
            holder.imgIcon.setImageDrawable(item.icon)
        } else {
            holder.imgIcon.setImageResource(AndroidR.drawable.sym_def_app_icon)
        }

        holder.btnRemove.setOnClickListener {
            onRemove(item)
        }
    }

    fun updateItems(newItems: List<ExcludedAppItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgIcon: ImageView = view.findViewById(R.id.imgExcludedIcon)
        val tvLabel: TextView = view.findViewById(R.id.tvExcludedLabel)
        val tvPackage: TextView = view.findViewById(R.id.tvExcludedPackage)
        val btnRemove: Button = view.findViewById(R.id.btnRemoveExcluded)
    }
}