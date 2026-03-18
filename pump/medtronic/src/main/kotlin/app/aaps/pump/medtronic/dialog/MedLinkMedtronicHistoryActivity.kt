package app.aaps.pump.medtronic.dialog

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.common.defs.PumpHistoryEntryGroup
import app.aaps.pump.medtronic.R
import app.aaps.pump.medtronic.comm.history.pump.PumpHistoryEntry
import app.aaps.pump.medtronic.data.MedLinkMedtronicHistoryData
import dagger.android.DaggerActivity
import javax.inject.Inject

class MedLinkMedtronicHistoryActivity : DaggerActivity() {
    @JvmField
    @Inject
    var medtronicHistoryData: MedLinkMedtronicHistoryData? = null


    @Inject
    lateinit var resourceHelper: ResourceHelper

    lateinit var historyTypeSpinner: Spinner
    var statusView: TextView? = null
    var recyclerView: RecyclerView? = null
    var llm: LinearLayoutManager? = null

    var filteredHistoryList: MutableList<PumpHistoryEntry> = ArrayList()

    var recyclerViewAdapter: RecyclerViewAdapter? = null
    var manualChange: Boolean = false

    lateinit var typeListFull: List<TypeList>


    private fun filterHistory(group: PumpHistoryEntryGroup?) {
        this.filteredHistoryList.clear()

        val list: MutableList<PumpHistoryEntry> = ArrayList<PumpHistoryEntry>()
        list.addAll(medtronicHistoryData!!.allHistory)

        //LOG.debug("Items on full list: {}", list.size());
        if (group == PumpHistoryEntryGroup.All) {
            this.filteredHistoryList.addAll(list)
        } else {
            for (pumpHistoryEntry in list) {
                if (pumpHistoryEntry.entryType.group == group) {
                    this.filteredHistoryList.add(pumpHistoryEntry)
                }
            }
        }

        if (this.recyclerViewAdapter != null) {
            this.recyclerViewAdapter!!.historyList = this.filteredHistoryList
            this.recyclerViewAdapter!!.notifyDataSetChanged()
        }

        //LOG.debug("Items on filtered list: {}", filteredHistoryList.size());
    }


    override fun onResume() {
        super.onResume()
        filterHistory(selectedGroup)
        setHistoryTypeSpinner()
    }


    private fun setHistoryTypeSpinner() {
        this.manualChange = true

        for (i in typeListFull!!.indices) {
            if (typeListFull!!.get(i)!!.entryGroup == selectedGroup) {
                historyTypeSpinner!!.setSelection(i)
                break
            }
        }

        SystemClock.sleep(200)
        this.manualChange = false
    }


    override fun onPause() {
        super.onPause()
    }


    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.medtronic_history_activity)

        historyTypeSpinner = findViewById<Spinner>(R.id.medtronic_historytype)
        statusView = findViewById<TextView>(R.id.medtronic_historystatus)
        recyclerView = findViewById<RecyclerView>(R.id.medtronic_history_recyclerview)

        recyclerView!!.setHasFixedSize(true)
        llm = LinearLayoutManager(this)
        recyclerView!!.setLayoutManager(llm)

        recyclerViewAdapter = RecyclerViewAdapter(filteredHistoryList)
        recyclerView!!.setAdapter(recyclerViewAdapter)

        statusView!!.setVisibility(View.GONE)

        typeListFull =
            getTypeList(PumpHistoryEntryGroup.Companion.getTranslatedList(resourceHelper!!))

        val spinnerAdapter =
            ArrayAdapter(this, app.aaps.core.ui.R.layout.spinner_centered, typeListFull)
        historyTypeSpinner!!.setAdapter(spinnerAdapter)

        historyTypeSpinner!!.setOnItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (manualChange) return
                val selected = historyTypeSpinner!!.getSelectedItem() as TypeList
                showingType = selected
                selectedGroup = selected.entryGroup
                filterHistory(selectedGroup)
            }


            override fun onNothingSelected(parent: AdapterView<*>?) {
                if (manualChange) return
                filterHistory(PumpHistoryEntryGroup.All)
            }
        })
    }


    private fun getTypeList(list: List<PumpHistoryEntryGroup>): List<TypeList> {
        val typeList = ArrayList<TypeList>()

        for (pumpHistoryEntryGroup in list) {
            typeList.add(TypeList(pumpHistoryEntryGroup))
        }

        return typeList
    }

    class TypeList internal constructor(entryGroup: PumpHistoryEntryGroup) {
        var entryGroup: PumpHistoryEntryGroup?
        var name: String?


        init {
            this.entryGroup = entryGroup
            this.name = entryGroup.translated
        }


        override fun toString(): String {
            return name!!
        }
    }

    class RecyclerViewAdapter internal constructor(var historyList: MutableList<PumpHistoryEntry>) :
        RecyclerView.Adapter<RecyclerViewAdapter.HistoryViewHolder>() {
//        fun setHistoryList(historyList: MutableList<PumpHistoryEntry>) {
//            // this.historyList.clear();
//            // this.historyList.addAll(historyList);
//
//            this.historyList = historyList
//
//            // this.notifyDataSetChanged();
//        }


        override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): HistoryViewHolder {
            val v = LayoutInflater.from(viewGroup.getContext()).inflate(
                R.layout.medtronic_history_item,  //
                viewGroup, false
            )
            return HistoryViewHolder(v)
        }


        override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
            val record = historyList.get(position)

            if (record != null) {
                holder.timeView.setText(record.dateTimeString)
                holder.typeView.setText(record.entryType.description)
                holder.valueView.setText(record.displayableValue)
            }
        }


        override fun getItemCount(): Int {
            return historyList.size
        }


        override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
            super.onAttachedToRecyclerView(recyclerView)
        }

        class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            var timeView: TextView
            var typeView: TextView
            var valueView: TextView


            init {
                // cv = (CardView)itemView.findViewById(R.id.rileylink_history_item);
                timeView = itemView.findViewById<TextView>(R.id.medtronic_history_time)
                typeView = itemView.findViewById<TextView>(R.id.medtronic_history_source)
                valueView = itemView.findViewById<TextView>(R.id.medtronic_history_description)
            }
        }
    }

    companion object {
        var showingType: TypeList? = null
        var selectedGroup: PumpHistoryEntryGroup? = PumpHistoryEntryGroup.All
    }
}
