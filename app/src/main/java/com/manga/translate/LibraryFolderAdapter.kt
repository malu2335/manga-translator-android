package com.manga.translate

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.manga.translate.databinding.ItemFolderBinding

class LibraryFolderAdapter(
    private val onClick: (FolderItem) -> Unit,
    private val onDelete: (FolderItem) -> Unit,
    private val onRename: (FolderItem) -> Unit
) : ListAdapter<FolderItem, LibraryFolderAdapter.FolderViewHolder>(DiffCallback) {
    private var actionPosition: Int? = null

    fun submit(list: List<FolderItem>) {
        submitList(list)
        val current = actionPosition
        if (current != null && current >= list.size) {
            actionPosition = null
        }
    }

    fun clearActionSelection() {
        val previous = actionPosition
        actionPosition = null
        if (previous != null) {
            notifyItemChanged(previous)
        }
    }

    fun clearActionSelectionIfTouchedOutside(recyclerView: RecyclerView, event: MotionEvent) {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return
        val current = actionPosition ?: return
        val holder = recyclerView.findViewHolderForAdapterPosition(current) ?: return
        val itemView = holder.itemView
        val tappedInsideExpandedItem =
            event.x >= itemView.left &&
                event.x <= itemView.right &&
                event.y >= itemView.top &&
                event.y <= itemView.bottom
        if (!tappedInsideExpandedItem) {
            clearActionSelection()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FolderViewHolder(binding, onClick, onDelete, onRename, ::toggleActionPosition)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        holder.bind(getItem(position), position == actionPosition)
    }

    class FolderViewHolder(
        private val binding: ItemFolderBinding,
        private val onClick: (FolderItem) -> Unit,
        private val onDelete: (FolderItem) -> Unit,
        private val onRename: (FolderItem) -> Unit,
        private val onToggleAction: (Int) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FolderItem, showActions: Boolean) {
            binding.folderName.text = item.folder.name
            val context = binding.root.context
            binding.folderMeta.text = if (item.isCollection) {
                context.getString(R.string.folder_collection_meta, item.chapterCount, item.imageCount)
            } else {
                context.getString(R.string.folder_image_count, item.imageCount)
            }
            binding.folderActions.visibility = if (showActions) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                onToggleAction(bindingAdapterPosition)
                true
            }
            binding.folderDelete.setOnClickListener { onDelete(item) }
            binding.folderRename.setOnClickListener { onRename(item) }
        }
    }

    private fun toggleActionPosition(position: Int) {
        if (position == RecyclerView.NO_POSITION) return
        val previous = actionPosition
        actionPosition = if (previous == position) null else position
        if (previous != null) {
            notifyItemChanged(previous)
        }
        notifyItemChanged(position)
    }

    private companion object {
        val DiffCallback = object : DiffUtil.ItemCallback<FolderItem>() {
            override fun areItemsTheSame(oldItem: FolderItem, newItem: FolderItem): Boolean {
                return oldItem.folder.absolutePath == newItem.folder.absolutePath
            }

            override fun areContentsTheSame(oldItem: FolderItem, newItem: FolderItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
