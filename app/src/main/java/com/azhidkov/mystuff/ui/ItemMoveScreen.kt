package com.azhidkov.mystuff.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.azhidkov.mystuff.Inventory
import com.azhidkov.mystuff.Item
import com.azhidkov.mystuff.R

internal data class ItemMoveTreeRow(
    val item: Item,
    val depth: Int,
    val isExpandable: Boolean,
    val isExpanded: Boolean,
    val isSelectable: Boolean,
    val isSelected: Boolean,
    val isCurrentParent: Boolean,
)

internal fun initialMoveExpandedBranch(inventory: Inventory, item: Item): List<String> {
    val currentParentItemId = item.parentItemId ?: return emptyList()
    return inventory.pathTo(currentParentItemId).map(Item::id)
}

internal fun toggleMoveExpandedBranch(
    inventory: Inventory,
    expandedBranch: List<String>,
    itemId: String,
): List<String> {
    val expandedIndex = expandedBranch.indexOf(itemId)
    return if (expandedIndex >= 0) {
        expandedBranch.take(expandedIndex)
    } else {
        inventory.pathTo(itemId).map(Item::id)
    }
}

internal fun itemMoveTreeRows(
    inventory: Inventory,
    movedItem: Item,
    candidateIds: Set<String>,
    selectedParentItemId: String?,
    expandedBranch: List<String>,
): List<ItemMoveTreeRow> {
    val hiddenItemIds = inventory.allItems
        .asSequence()
        .filter { item ->
            inventory.pathTo(item.id).any { ancestor -> ancestor.id == movedItem.id }
        }
        .mapTo(mutableSetOf(), Item::id)
    val expandedItemIds = expandedBranch.toSet()
    val rows = mutableListOf<ItemMoveTreeRow>()

    fun append(item: Item, depth: Int) {
        if (item.id in hiddenItemIds) return
        val children = inventory.childrenOf(item.id).filterNot { it.id in hiddenItemIds }
        val isExpanded = children.isNotEmpty() && item.id in expandedItemIds
        rows += ItemMoveTreeRow(
            item = item,
            depth = depth,
            isExpandable = children.isNotEmpty(),
            isExpanded = isExpanded,
            isSelectable = item.id in candidateIds,
            isSelected = item.id == selectedParentItemId,
            isCurrentParent = item.id == movedItem.parentItemId,
        )
        if (isExpanded) {
            children.forEach { child -> append(child, depth + 1) }
        }
    }

    append(inventory.item(inventory.rootItemId), depth = 0)
    return rows
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ItemMoveScreen(
    inventory: Inventory,
    item: Item,
    candidates: List<Item>,
    selectedParentItemId: String?,
    operationInProgress: Boolean,
    onSelect: (String) -> Unit,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    var expandedBranch by remember(item.id, item.parentItemId) {
        mutableStateOf(initialMoveExpandedBranch(inventory, item))
    }
    val rows = itemMoveTreeRows(
        inventory = inventory,
        movedItem = item,
        candidateIds = candidates.mapTo(mutableSetOf(), Item::id),
        selectedParentItemId = selectedParentItemId,
        expandedBranch = expandedBranch,
    )

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.move_item_title)) },
                navigationIcon = {
                    TextButton(onClick = onClose, enabled = !operationInProgress) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        },
        bottomBar = {
            Surface(shadowElevation = 4.dp) {
                Button(
                    onClick = onConfirm,
                    enabled = selectedParentItemId != null && !operationInProgress,
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                        .fillMaxWidth(),
                ) {
                    if (operationInProgress) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        Text(stringResource(R.string.move_item_confirm))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.move_item_body, item.name),
                    modifier = Modifier.padding(bottom = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (candidates.isEmpty()) {
                item { Text(stringResource(R.string.move_item_no_targets)) }
            } else {
                items(rows, key = { row -> row.item.id }) { row ->
                    ItemMoveTreeRow(
                        row = row,
                        enabled = !operationInProgress,
                        onExpand = {
                            expandedBranch = toggleMoveExpandedBranch(
                                inventory = inventory,
                                expandedBranch = expandedBranch,
                                itemId = row.item.id,
                            )
                        },
                        onSelect = { onSelect(row.item.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemMoveTreeRow(
    row: ItemMoveTreeRow,
    enabled: Boolean,
    onExpand: () -> Unit,
    onSelect: () -> Unit,
) {
    val selectable = row.isSelectable && enabled
    val rowModifier = Modifier
        .padding(start = (row.depth * 20).dp)
        .fillMaxWidth()
        .heightIn(min = 52.dp)
    Surface(
        modifier = if (row.isSelectable) {
            rowModifier.selectable(
                selected = row.isSelected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            )
        } else {
            rowModifier
        },
        color = if (row.isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.isExpandable) {
                IconButton(onClick = onExpand, enabled = enabled) {
                    Icon(
                        painter = painterResource(
                            if (row.isExpanded) {
                                R.drawable.ic_expand_more
                            } else {
                                R.drawable.ic_chevron_right
                            },
                        ),
                        contentDescription = stringResource(
                            if (row.isExpanded) {
                                R.string.collapse_item
                            } else {
                                R.string.expand_item
                            },
                            row.item.name,
                        ),
                    )
                }
            } else {
                Spacer(Modifier.width(48.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = row.item.name,
                    color = if (row.isSelectable) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (row.isCurrentParent) {
                    Text(
                        text = stringResource(R.string.current_parent_item),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (row.isSelectable) {
                RadioButton(
                    selected = row.isSelected,
                    onClick = null,
                    enabled = selectable,
                )
            } else {
                Spacer(Modifier.width(48.dp))
            }
        }
    }
}
