package app.aaps.pump.common.hw.medlink.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.aaps.core.interfaces.pump.defs.PumpDeviceState
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.pump.common.hw.rileylink.R
import app.aaps.pump.common.hw.rileylink.RileyLinkUtil
import app.aaps.pump.common.hw.rileylink.data.RLHistoryItem
import app.aaps.pump.common.hw.rileylink.databinding.RileylinkStatusHistoryItemBinding
import dagger.android.support.DaggerFragment
import java.util.Collections
import javax.inject.Inject

/**
 * Created by andy on 5/19/18.
 */
class MedLinkStatusHistoryFragment : DaggerFragment() {
    @Inject
    lateinit var rileyLinkUtil: RileyLinkUtil

    @Inject
    lateinit var resourceHelper: ResourceHelper

    @Inject
    lateinit var dateUtil: DateUtil

    var recyclerView: RecyclerView? = null
    var recyclerViewAdapter: RecyclerViewAdapter? = null

    var llm: LinearLayoutManager? = null
    var filteredHistoryList: MutableList<RLHistoryItem> = ArrayList<RLHistoryItem>()


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.rileylink_status_history, container, false)

        recyclerView = rootView.findViewById<View?>(R.id.history_list) as RecyclerView

        recyclerView!!.setHasFixedSize(true)
        llm = LinearLayoutManager(rootView.getContext())
        recyclerView!!.setLayoutManager(llm)

        recyclerViewAdapter = RecyclerViewAdapter(filteredHistoryList)
        recyclerView!!.setAdapter(recyclerViewAdapter)

        return rootView
    }


    override fun onStart() {
        super.onStart()

        refreshData()
    }


    //    @Override
    fun refreshData() {
        if (rileyLinkUtil!!.rileyLinkHistory != null) {
            recyclerViewAdapter!!.addItemsAndClean(rileyLinkUtil!!.rileyLinkHistory)
        }
    }


    inner class RecyclerViewAdapter internal constructor(private val historyList: MutableList<RLHistoryItem>) : RecyclerView.Adapter<RecyclerViewAdapter.HistoryViewHolder>() {
         fun addItemsAndClean(items: MutableList<RLHistoryItem>) {
            this.historyList.clear()

            items.sortWith(RLHistoryItem.Comparator())

            for (item in items) {
                if (!historyList.contains(item) && isValidItem(item)) {
                    historyList.add(item)
                }
            }

            notifyDataSetChanged()
        }


        private fun isValidItem(item: RLHistoryItem): Boolean {
            val pumpState = item.pumpDeviceState

            //
            if ((pumpState == PumpDeviceState.Sleeping ||  //
                        pumpState == PumpDeviceState.Active ||  //
                        pumpState == PumpDeviceState.WakingUp //
                        )
            ) return false

            return true
        }


        override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): HistoryViewHolder {
            val v = LayoutInflater.from(viewGroup.getContext()).inflate(
                R.layout.rileylink_status_history_item,  //
                viewGroup, false
            )
            return HistoryViewHolder(v)
        }


        override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
            val item = historyList.get(position)

            if (item != null) {
                holder.timeView.setText(
                    dateUtil!!.dateAndTimeAndSecondsString(
                        item.dateTime.toDateTime().getMillis()
                    )
                )
                holder.typeView.setText(item.source.desc)
                holder.valueView.setText(item.getDescription(resourceHelper!!))
            }
        }


        override fun getItemCount(): Int {
            return historyList.size
        }


        override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
            super.onAttachedToRecyclerView(recyclerView)
        }

        inner class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            var timeView: TextView
            var typeView: TextView
            var valueView: TextView


            init {
                timeView = itemView.findViewById<View?>(R.id.history_time) as TextView
                typeView = itemView.findViewById<View?>(R.id.history_source) as TextView
                valueView = itemView.findViewById<View?>(R.id.history_description) as TextView
            }
        }
    }
}
