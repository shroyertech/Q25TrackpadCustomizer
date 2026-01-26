package tech.shroyer.q25trackpadcustomizer

import android.view.View
import android.widget.AdapterView
import android.widget.Spinner

// Simplify Spinner onItemSelectedListener usage
fun Spinner.setOnItemSelectedListenerSimple(onSelected: (position: Int) -> Unit) {
    this.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) {
        }

        override fun onItemSelected(
            parent: AdapterView<*>?,
            view: View?,
            position: Int,
            id: Long
        ) {
            onSelected(position)
        }
    }
}