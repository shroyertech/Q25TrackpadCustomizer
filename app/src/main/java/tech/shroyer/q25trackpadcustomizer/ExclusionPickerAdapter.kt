package tech.shroyer.q25trackpadcustomizer

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// Display valid apps to add to the exclusion list
class ExclusionPickerAdapter(
    private val context: Context,
    items: MutableList<AppItem>,
    private val onSelected: (AppItem) -> Unit
) : RecyclerView.Adapter<ExclusionPickerAdapter.ViewHolder>() {

    private val allItems: MutableList<AppItem> = items
    private val filteredItems: MutableList<AppItem> = items.toMutableList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_exclude_picker_app, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = filteredItems.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = filteredItems[position]
        holder.tvLabel.text = app.label
        holder.tvPackage.text = app.packageName
        holder.imgIcon.setImageDrawable(app.icon)

        holder.itemView.setOnClickListener {
            onSelected(app)
        }
    }

    fun filter(query: String) {
        val lower = query.lowercase()
        filteredItems.clear()
        if (lower.isEmpty()) {
            filteredItems.addAll(allItems)
        } else {
            filteredItems.addAll(
                allItems.filter {
                    it.label.lowercase().contains(lower) ||
                            it.packageName.lowercase().contains(lower)
                }
            )
        }
        notifyDataSetChanged()
    }

    fun removeApp(packageName: String) {
        val iteratorAll = allItems.iterator()
        while (iteratorAll.hasNext()) {
            if (iteratorAll.next().packageName == packageName) {
                iteratorAll.remove()
                break
            }
        }
        val iteratorFiltered = filteredItems.iterator()
        while (iteratorFiltered.hasNext()) {
            if (iteratorFiltered.next().packageName == packageName) {
                iteratorFiltered.remove()
                break
            }
        }
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgIcon: ImageView = view.findViewById(R.id.imgPickerIcon)
        val tvLabel: TextView = view.findViewById(R.id.tvPickerLabel)
        val tvPackage: TextView = view.findViewById(R.id.tvPickerPackage)
    }
}