package com.noc.app.ui.image

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.noc.app.data.db.CreationEntity
import com.noc.app.data.db.NocDatabase
import com.noc.app.ui.chat.NocSheet
import com.noc.app.ui.components.pressable
import com.noc.app.ui.theme.Noc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Escolher uma das criações (já no PC e neste celular) como guia ou origem. */
@Composable
fun CreationPickerSheet(db: NocDatabase, title: String, onPick: (CreationEntity) -> Unit, onDismiss: () -> Unit) {
    val c = Noc.colors
    val all by db.creations().observeAll().collectAsState(initial = null)
    NocSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = c.text, modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp))
            val list = all.orEmpty().filter { it.thumbPath != null || it.path != null }
            if (all != null && list.isEmpty())
                Text("Nenhuma criação ainda.", style = MaterialTheme.typography.bodyMedium, color = c.text3, modifier = Modifier.padding(12.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(bottom = 16.dp),
            ) {
                items(list, key = { it.id }) { cr ->
                    val path = cr.thumbPath ?: cr.path
                    val bmp by produceState<Bitmap?>(null, path) { value = withContext(Dispatchers.IO) { path?.let { decodeSampled(it, 300) } } }
                    Box(
                        Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(c.surface2).pressable { onPick(cr); onDismiss() }
                            .semantics { contentDescription = "Criação: ${cr.prompt.take(60)}" },
                    ) {
                        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                }
            }
        }
    }
}
