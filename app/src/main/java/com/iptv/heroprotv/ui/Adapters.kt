package com.iptv.heroprotv.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.iptv.heroprotv.R
import com.iptv.heroprotv.model.ChannelItem
import com.iptv.heroprotv.model.EpisodeItem
import com.iptv.heroprotv.model.UserProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ChannelAdapter(
    private val onItemClick: (ChannelItem, Int) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

    private var items: List<ChannelItem> = emptyList()
    private var selectedPosition: Int = -1

    fun submitList(newItems: List<ChannelItem>) {
        items = newItems
        selectedPosition = -1
        notifyDataSetChanged()
    }

    fun setSelectedPosition(pos: Int) {
        val prev = selectedPosition
        selectedPosition = pos
        if (prev >= 0 && prev < items.size) notifyItemChanged(prev)
        if (pos >= 0 && pos < items.size) notifyItemChanged(pos)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.nameText.text = item.name
        holder.badgeText.text = item.group.ifBlank { "Geral" }

        val stripeColor = when {
            position % 3 == 0 -> ContextCompat.getColor(holder.itemView.context, R.color.marvel_yellow)
            position % 3 == 2 -> ContextCompat.getColor(holder.itemView.context, R.color.dc_blue)
            else -> ContextCompat.getColor(holder.itemView.context, R.color.marvel_red)
        }
        holder.leftStripe.setBackgroundColor(stripeColor)
        holder.itemView.isSelected = (position == selectedPosition)

        holder.itemView.setOnClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                setSelectedPosition(adapterPos)
                onItemClick(item, adapterPos)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val leftStripe: View = view.findViewById(R.id.left_stripe)
        val nameText: TextView = view.findViewById(R.id.channel_name)
        val badgeText: TextView = view.findViewById(R.id.channel_badge)
    }
}

class ProfileAdapter(
    private val onActivate: (UserProfile) -> Unit,
    private val onDelete: (UserProfile) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.ViewHolder>() {

    private var profiles: List<UserProfile> = emptyList()
    private var activeId: String? = null

    fun submitList(list: List<UserProfile>, activeProfileId: String?) {
        profiles = list.sortedByDescending { it.lastUsed }
        activeId = activeProfileId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_profile, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val p = profiles[position]
        val emoji = if (p.universe == "marvel") "🦸" else "🦇"
        holder.heroName.text = "$emoji ${p.hero}"

        val stripeColor = if (position % 2 == 0) {
            ContextCompat.getColor(holder.itemView.context, R.color.dc_blue)
        } else {
            ContextCompat.getColor(holder.itemView.context, R.color.marvel_red)
        }
        holder.leftStripe.setBackgroundColor(stripeColor)

        holder.bulkBadge.visibility = if (p.bulkOrigin) View.VISIBLE else View.GONE

        val validityInfo = getValidityTextAndColor(p.validity)
        holder.validityBadge.text = validityInfo.first
        holder.validityBadge.setBackgroundColor(validityInfo.second)

        val connStr = if (p.maxConnections != null) "${p.activeConnections}/${p.maxConnections}" else "${p.activeConnections}"
        holder.metaText.text = "📡 ${p.sourceType.uppercase()}   🎬 ${p.contentCounts.live}   🎥 ${p.contentCounts.vod}   📽️ ${p.contentCounts.series}   🔌 $connStr"

        holder.itemView.isSelected = (p.id == activeId)

        holder.itemView.setOnClickListener { onActivate(p) }
        holder.btnActivate.setOnClickListener { onActivate(p) }
        holder.btnDelete.setOnClickListener { onDelete(p) }
    }

    private fun getValidityTextAndColor(validity: String?): Pair<String, Int> {
        if (validity.isNullOrBlank()) return Pair("Sem validade", Color.parseColor("#475569"))
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val expDate: Date = sdf.parse(validity) ?: return Pair("Sem validade", Color.parseColor("#475569"))
            val diffDays = Math.ceil((expDate.time - System.currentTimeMillis()) / 86400000.0).toInt()
            when {
                diffDays < 0 -> Pair("Expirado", Color.parseColor("#E62429"))
                diffDays <= 7 -> Pair("${diffDays}d", Color.parseColor("#E62429"))
                else -> {
                    val brFormat = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR"))
                    Pair("Até ${brFormat.format(expDate)}", Color.parseColor("#16A34A"))
                }
            }
        } catch (e: Exception) {
            Pair("Sem validade", Color.parseColor("#475569"))
        }
    }

    override fun getItemCount(): Int = profiles.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val leftStripe: View = view.findViewById(R.id.profile_stripe)
        val heroName: TextView = view.findViewById(R.id.profile_hero)
        val bulkBadge: TextView = view.findViewById(R.id.profile_bulk_badge)
        val validityBadge: TextView = view.findViewById(R.id.profile_validity_badge)
        val metaText: TextView = view.findViewById(R.id.profile_meta)
        val btnActivate: Button = view.findViewById(R.id.btn_profile_activate)
        val btnDelete: Button = view.findViewById(R.id.btn_profile_delete)
    }
}

class SeasonAdapter(
    private val onSeasonSelected: (String) -> Unit
) : RecyclerView.Adapter<SeasonAdapter.ViewHolder>() {

    private var seasons: List<Pair<String, Int>> = emptyList()
    private var selectedSeason: String? = null

    fun submitSeasons(data: Map<String, List<EpisodeItem>>, initialSelected: String?) {
        seasons = data.map { Pair(it.key, it.value.size) }
        selectedSeason = initialSelected
        notifyDataSetChanged()
    }

    fun setSelectedSeason(seasonNum: String) {
        selectedSeason = seasonNum
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_season, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (seasonNum, count) = seasons[position]
        holder.titleText.text = "Temporada $seasonNum"
        holder.countText.text = count.toString()

        val stripeColor = when {
            position % 3 == 0 -> ContextCompat.getColor(holder.itemView.context, R.color.marvel_red)
            position % 3 == 2 -> ContextCompat.getColor(holder.itemView.context, R.color.dc_blue)
            else -> ContextCompat.getColor(holder.itemView.context, R.color.marvel_yellow)
        }
        holder.leftStripe.setBackgroundColor(stripeColor)
        holder.itemView.isSelected = (seasonNum == selectedSeason)

        holder.itemView.setOnClickListener {
            setSelectedSeason(seasonNum)
            onSeasonSelected(seasonNum)
        }
    }

    override fun getItemCount(): Int = seasons.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val leftStripe: View = view.findViewById(R.id.season_stripe)
        val titleText: TextView = view.findViewById(R.id.season_title)
        val countText: TextView = view.findViewById(R.id.season_count)
    }
}

class EpisodeAdapter(
    private val onEpisodeClick: (EpisodeItem) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

    private var episodes: List<EpisodeItem> = emptyList()
    private var currentSeason: String = "1"
    private var activeEpId: String? = null

    fun submitEpisodes(list: List<EpisodeItem>, seasonNum: String) {
        episodes = list
        currentSeason = seasonNum
        notifyDataSetChanged()
    }

    fun setActiveEpisode(id: String) {
        activeEpId = id
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val ep = episodes[position]
        val epNumPadded = ep.episodeNum.padStart(2, '0')
        holder.titleText.text = "E$epNumPadded — ${ep.title}"
        val durPart = if (!ep.duration.isNullOrBlank()) " • ${ep.duration}" else ""
        holder.metaText.text = "T$currentSeason • ${ep.containerExtension.uppercase()}$durPart"

        holder.itemView.isSelected = (ep.id == activeEpId)
        holder.itemView.setOnClickListener {
            setActiveEpisode(ep.id)
            onEpisodeClick(ep)
        }
    }

    override fun getItemCount(): Int = episodes.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val titleText: TextView = view.findViewById(R.id.ep_title)
        val metaText: TextView = view.findViewById(R.id.ep_meta)
    }
}
