package com.noc.app.ui.image

import androidx.navigation.NavHostController
import com.noc.app.AppContainer
import com.noc.app.chat.ImageEdit
import com.noc.app.chat.ImageInput
import com.noc.app.chat.ImageJob
import com.noc.app.chat.ImageOptions
import com.noc.app.data.db.CreationEntity
import com.noc.app.ui.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * O que dá para fazer com uma imagem pronta — igual no chat e na galeria. Toda edição vira um pedido normal na conversa
 * da imagem (fila, progresso, notificação, segundo plano); guias e "reutilizar" abrem o modo Criar imagem já preenchido.
 */
class CreationActions(
    private val container: AppContainer,
    private val nav: NavHostController,
    private val scope: CoroutineScope,
    private val toast: (String) -> Unit,
    /** Conversa aberta agora (no chat): a edição aparece nela sem navegar de novo. */
    private val currentConversation: () -> String? = { null },
    private val onStayed: () -> Unit = {},
) {
    private val images get() = container.chat.images

    private fun source(cr: CreationEntity) = ImageInput.pc(cr.id, cr.width, cr.height, cr.thumbPath)

    private fun run(cr: CreationEntity, edit: ImageEdit, prompt: String = "") {
        if (!images.supported()) { toast("O Companion do PC ainda não edita imagens. Atualize o Noc Companion."); return }
        scope.launch {
            runCatching {
                val conv = images.edit(cr, edit, prompt, ImageOptions(), newConversation = { container.chat.newConversation() })
                if (conv == currentConversation()) onStayed() else nav.navigate(Routes.chat(conv))
            }.onFailure { toast("Não foi possível pedir a edição.") }
        }
    }

    fun paint(cr: CreationEntity) = nav.navigate(Routes.imageEdit(cr.id, "paint"))
    fun expand(cr: CreationEntity) = nav.navigate(Routes.imageEdit(cr.id, "expand"))
    fun variation(cr: CreationEntity, strength: Double, count: Int) = run(cr, ImageEdit("img2img", source = source(cr), strength = strength, count = count))
    fun hd(cr: CreationEntity) = run(cr, ImageEdit("hd", source = source(cr)))
    fun upscale(cr: CreationEntity, factor: Int) = run(cr, ImageEdit("upscale", source = source(cr), upscale = factor))
    fun detail(cr: CreationEntity) = run(cr, ImageEdit("detail", source = source(cr)))
    fun recreate(cr: CreationEntity) = run(cr, ImageEdit("recreate", recreate = cr.id))

    /** Abre o modo Criar imagem com esta imagem como guia (referência, pose, estrutura ou contornos). */
    fun useAsGuide(cr: CreationEntity, guide: Guide) {
        scope.launch {
            val base = baseOptions(cr)
            val input = source(cr)
            val o = if (guide.control == null) base.copy(reference = input) else base.copy(control = guide.control, controlInput = input)
            images.pending.value = o to null
            nav.navigate(Routes.chat(Routes.NEW, image = true))
        }
    }

    /** Abre o modo Criar imagem com o pedido e as escolhas desta imagem. */
    fun reusePrompt(cr: CreationEntity) {
        scope.launch {
            images.pending.value = baseOptions(cr).copy(seed = null) to cr.prompt
            nav.navigate(Routes.chat(cr.conversationId?.takeIf { container.db.conversations().get(it) != null } ?: Routes.NEW, image = true))
        }
    }

    private suspend fun baseOptions(cr: CreationEntity): ImageOptions {
        val job = cr.messageId?.let { container.db.messages().get(it) }?.let { ImageJob.decode(it.imageJson) }
        val o = job?.options ?: ImageOptions()
        // mesmo modelo da imagem, quando ele é um especialista
        val m = images.caps.value?.model(cr.model)
        return if (m?.specialist == true) o.copy(engine = "model", model = m.id, reference = null, control = null, controlInput = null)
        else o.copy(reference = null, control = null, controlInput = null)
    }
}
