package com.manga.translate

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.manga.translate.databinding.ItemFolderBinding
import java.io.File

class LibraryFolderAdapter(
    private val onClick: (FolderItem) -> Unit,
    private val onDelete: (FolderItem) -> Unit,
    private val onRename: (FolderItem) -> Unit,
    private val onMove: (FolderItem) -> Unit,
    private val onSelectionChanged: (() -> Unit)? = null,
    private val onItemLongPress: ((FolderItem) -> Unit)? = null
) : ListAdapter<FolderItem, LibraryFolderAdapter.FolderViewHolder>(DiffCallback) {
    private var actionPosition: Int? = null
    private val selectedPaths = LinkedHashSet<String>()
    private var selectionMode = false

    fun submit(list: List<FolderItem>) {
        if (selectionMode) {
            val validPaths = list.map { it.folder.absolutePath }.toHashSet()
            selectedPaths.retainAll(validPaths)
        }
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
        if (selectionMode) return
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

    fun setSelectionMode(enabled: Boolean) {
        selectionMode = enabled
        if (enabled) {
            clearActionSelection()
        } else {
            selectedPaths.clear()
        }
        if (currentList.isNotEmpty()) {
            notifyItemRangeChanged(0, currentList.size)
        }
    }

    fun toggleSelectionAndNotify(folder: File) {
        val path = folder.absolutePath
        if (!selectedPaths.add(path)) {
            selectedPaths.remove(path)
        }
        val index = currentList.indexOfFirst { it.folder.absolutePath == path }
        if (index >= 0) {
            notifyItemChanged(index)
        }
        onSelectionChanged?.invoke()
    }

    fun selectAll() {
        selectedPaths.clear()
        currentList.forEach { selectedPaths.add(it.folder.absolutePath) }
        if (currentList.isNotEmpty()) {
            notifyItemRangeChanged(0, currentList.size)
        }
        onSelectionChanged?.invoke()
    }

    fun clearSelection() {
        selectedPaths.clear()
        if (currentList.isNotEmpty()) {
            notifyItemRangeChanged(0, currentList.size)
        }
        onSelectionChanged?.invoke()
    }

    fun getSelectedFolders(): List<File> {
        return currentList.filter { selectedPaths.contains(it.folder.absolutePath) }.map { it.folder }
    }

    fun selectedCount(): Int = selectedPaths.size

    fun areAllSelected(): Boolean {
        return currentList.isNotEmpty() && selectedPaths.size == currentList.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FolderViewHolder(
            binding = binding,
            onClick = onClick,
            onDelete = onDelete,
            onRename = onRename,
            onMove = onMove,
            onToggleAction = ::toggleActionPosition,
            onItemLongPress = onItemLongPress,
            onToggleSelection = ::toggleSelectionAndNotify
        )
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(
            item = item,
            showActions = position == actionPosition,
            selectionMode = selectionMode,
            selected = selectedPaths.contains(item.folder.absolutePath)
        )
    }

    class FolderViewHolder(
        private val binding: ItemFolderBinding,
        private val onClick: (FolderItem) -> Unit,
        private val onDelete: (FolderItem) -> Unit,
        private val onRename: (FolderItem) -> Unit,
        private val onMove: (FolderItem) -> Unit,
        private val onToggleAction: (Int) -> Unit,
        private val onItemLongPress: ((FolderItem) -> Unit)?,
        private val onToggleSelection: (File) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FolderItem, showActions: Boolean, selectionMode: Boolean, selected: Boolean) {
            binding.folderName.text = item.folder.name
            val context = binding.root.context
            binding.folderMeta.text = if (item.isCollection) {
                context.getString(R.string.folder_collection_meta, item.chapterCount, item.imageCount)
            } else {
                context.getString(R.string.folder_image_count, item.imageCount)
            }
            binding.folderCheck.visibility = if (selectionMode) View.VISIBLE else View.GONE
            binding.folderCheck.setOnCheckedChangeListener(null)
            binding.folderCheck.isChecked = selected
            binding.folderCheck.setOnCheckedChangeListener { _, _ ->
                onToggleSelection(item.folder)
            }
            binding.folderActions.visibility = if (showActions && !selectionMode) View.VISIBLE else View.GONE
            binding.folderMove.visibility = if (item.isCollection) View.GONE else View.VISIBLE
            binding.root.setOnLongClickListener {
                if (onItemLongPress != null) {
                    onItemLongPress.invoke(item)
                } else {
                    onToggleAction(bindingAdapterPosition)
                }
                true
            }
            binding.root.setOnClickListener {
                if (selectionMode) {
                    binding.folderCheck.toggle()
                } else {
                    onClick(item)
                }
            }
            binding.folderMove.setOnClickListener { onMove(item) }
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
