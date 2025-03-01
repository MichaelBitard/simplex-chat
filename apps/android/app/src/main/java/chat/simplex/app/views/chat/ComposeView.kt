package chat.simplex.app.views.chat

import ComposeVoiceView
import ComposeFileView
import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.ImageDecoder.DecodeException
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Reply
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.core.net.toUri
import chat.simplex.app.*
import chat.simplex.app.R
import chat.simplex.app.model.*
import chat.simplex.app.ui.theme.HighOrLowlight
import chat.simplex.app.views.chat.item.*
import chat.simplex.app.views.helpers.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.io.File

@Serializable
sealed class ComposePreview {
  @Serializable object NoPreview: ComposePreview()
  @Serializable class CLinkPreview(val linkPreview: LinkPreview?): ComposePreview()
  @Serializable class ImagePreview(val images: List<String>): ComposePreview()
  @Serializable class VoicePreview(val voice: String, val durationMs: Int, val finished: Boolean): ComposePreview()
  @Serializable class FilePreview(val fileName: String): ComposePreview()
}

@Serializable
sealed class ComposeContextItem {
  @Serializable object NoContextItem: ComposeContextItem()
  @Serializable class QuotedItem(val chatItem: ChatItem): ComposeContextItem()
  @Serializable class EditingItem(val chatItem: ChatItem): ComposeContextItem()
}

@Serializable
data class LiveMessage(
  val chatItem: ChatItem,
  val typedMsg: String,
  val sentMsg: String
)

@Serializable
data class ComposeState(
  val message: String = "",
  val liveMessage: LiveMessage? = null,
  val preview: ComposePreview = ComposePreview.NoPreview,
  val contextItem: ComposeContextItem = ComposeContextItem.NoContextItem,
  val inProgress: Boolean = false,
  val useLinkPreviews: Boolean
) {
  constructor(editingItem: ChatItem, liveMessage: LiveMessage? = null, useLinkPreviews: Boolean): this(
    editingItem.content.text,
    liveMessage,
    chatItemPreview(editingItem),
    ComposeContextItem.EditingItem(editingItem),
    useLinkPreviews = useLinkPreviews
  )

  val editing: Boolean
    get() =
      when (contextItem) {
        is ComposeContextItem.EditingItem -> true
        else -> false
      }
  val sendEnabled: () -> Boolean
    get() = {
      val hasContent = when (preview) {
        is ComposePreview.ImagePreview -> true
        is ComposePreview.VoicePreview -> true
        is ComposePreview.FilePreview -> true
        else -> message.isNotEmpty() || liveMessage != null
      }
      hasContent && !inProgress
    }
  val linkPreviewAllowed: Boolean
    get() =
      when (preview) {
        is ComposePreview.ImagePreview -> false
        is ComposePreview.VoicePreview -> false
        is ComposePreview.FilePreview -> false
        else -> useLinkPreviews
      }
  val linkPreview: LinkPreview?
    get() =
      when (preview) {
        is ComposePreview.CLinkPreview -> preview.linkPreview
        else -> null
      }

  val attachmentDisabled: Boolean
    get() {
      if (editing || liveMessage != null) return true
      return when (preview) {
        ComposePreview.NoPreview -> false
        is ComposePreview.CLinkPreview -> false
        else -> true
      }
    }

  companion object {
    fun saver(): Saver<MutableState<ComposeState>, *> = Saver(
      save = { json.encodeToString(serializer(), it.value) },
      restore = {
        mutableStateOf(json.decodeFromString(it))
      }
    )
  }
}

sealed class RecordingState {
  object NotStarted: RecordingState()
  class Started(val filePath: String, val progressMs: Int = 0): RecordingState()
  class Finished(val filePath: String, val durationMs: Int): RecordingState()

  val filePathNullable: String?
    get() = (this as? Started)?.filePath
}

fun chatItemPreview(chatItem: ChatItem): ComposePreview {
  return when (val mc = chatItem.content.msgContent) {
    is MsgContent.MCText -> ComposePreview.NoPreview
    is MsgContent.MCLink -> ComposePreview.CLinkPreview(linkPreview = mc.preview)
    is MsgContent.MCImage -> ComposePreview.ImagePreview(images = listOf(mc.image))
    is MsgContent.MCVoice -> ComposePreview.VoicePreview(voice = chatItem.file?.fileName ?: "", mc.duration / 1000, true)
    is MsgContent.MCFile -> {
      val fileName = chatItem.file?.fileName ?: ""
      ComposePreview.FilePreview(fileName)
    }
    is MsgContent.MCUnknown, null -> ComposePreview.NoPreview
  }
}

@Composable
fun ComposeView(
  chatModel: ChatModel,
  chat: Chat,
  composeState: MutableState<ComposeState>,
  attachmentOption: MutableState<AttachmentOption?>,
  showChooseAttachment: () -> Unit
) {
  val context = LocalContext.current
  val linkUrl = rememberSaveable { mutableStateOf<String?>(null) }
  val prevLinkUrl = rememberSaveable { mutableStateOf<String?>(null) }
  val pendingLinkUrl = rememberSaveable { mutableStateOf<String?>(null) }
  val cancelledLinks = rememberSaveable { mutableSetOf<String>() }
  val useLinkPreviews = chatModel.controller.appPrefs.privacyLinkPreviews.get()
  val smallFont = MaterialTheme.typography.body1.copy(color = MaterialTheme.colors.onBackground)
  val textStyle = remember { mutableStateOf(smallFont) }
  // attachments
  val chosenContent = rememberSaveable { mutableStateOf<List<UploadContent>>(emptyList()) }
  val audioSaver = Saver<MutableState<Pair<Uri, Int>?>, Pair<String, Int>>(
    save = { it.value.let { if (it == null) null else it.first.toString() to it.second } },
    restore = { mutableStateOf(Uri.parse(it.first) to it.second) }
  )
  val chosenAudio = rememberSaveable(saver = audioSaver) { mutableStateOf(null) }
  val chosenFile = rememberSaveable { mutableStateOf<Uri?>(null) }
  val cameraLauncher = rememberCameraLauncher { uri: Uri? ->
    if (uri != null) {
      val source = ImageDecoder.createSource(SimplexApp.context.contentResolver, uri)
      val bitmap = ImageDecoder.decodeBitmap(source)
      val imagePreview = resizeImageToStrSize(bitmap, maxDataSize = 14000)
      chosenContent.value = listOf(UploadContent.SimpleImage(uri))
      composeState.value = composeState.value.copy(preview = ComposePreview.ImagePreview(listOf(imagePreview)))
    }
  }
  val cameraPermissionLauncher = rememberPermissionLauncher { isGranted: Boolean ->
    if (isGranted) {
      cameraLauncher.launchWithFallback()
    } else {
      Toast.makeText(context, generalGetString(R.string.toast_permission_denied), Toast.LENGTH_SHORT).show()
    }
  }
  val processPickedImage = { uris: List<Uri>, text: String? ->
    val content = ArrayList<UploadContent>()
    val imagesPreview = ArrayList<String>()
    uris.forEach { uri ->
      val source = ImageDecoder.createSource(context.contentResolver, uri)
      val drawable = try {
        ImageDecoder.decodeDrawable(source)
      } catch (e: DecodeException) {
        AlertManager.shared.showAlertMsg(
          title = generalGetString(R.string.image_decoding_exception_title),
          text = generalGetString(R.string.image_decoding_exception_desc)
        )
        Log.e(TAG, "Error while decoding drawable: ${e.stackTraceToString()}")
        null
      }
      var bitmap: Bitmap? = if (drawable != null) ImageDecoder.decodeBitmap(source) else null
      if (drawable is AnimatedImageDrawable) {
        // It's a gif or webp
        val fileSize = getFileSize(context, uri)
        if (fileSize != null && fileSize <= MAX_FILE_SIZE) {
          content.add(UploadContent.AnimatedImage(uri))
        } else {
          bitmap = null
          AlertManager.shared.showAlertMsg(
            generalGetString(R.string.large_file),
            String.format(generalGetString(R.string.maximum_supported_file_size), formatBytes(MAX_FILE_SIZE))
          )
        }
      } else {
        content.add(UploadContent.SimpleImage(uri))
      }
      if (bitmap != null) {
        imagesPreview.add(resizeImageToStrSize(bitmap, maxDataSize = 14000))
      }
    }

    if (imagesPreview.isNotEmpty()) {
      chosenContent.value = content
      composeState.value = composeState.value.copy(message = text ?: composeState.value.message, preview = ComposePreview.ImagePreview(imagesPreview))
    }
  }
  val processPickedFile = { uri: Uri?, text: String? ->
    if (uri != null) {
      val fileSize = getFileSize(context, uri)
      if (fileSize != null && fileSize <= MAX_FILE_SIZE) {
        val fileName = getFileName(SimplexApp.context, uri)
        if (fileName != null) {
          chosenFile.value = uri
          composeState.value = composeState.value.copy(message = text ?: composeState.value.message, preview = ComposePreview.FilePreview(fileName))
        }
      } else {
        AlertManager.shared.showAlertMsg(
          generalGetString(R.string.large_file),
          String.format(generalGetString(R.string.maximum_supported_file_size), formatBytes(MAX_FILE_SIZE))
        )
      }
    }
  }
  val galleryLauncher = rememberLauncherForActivityResult(contract = PickMultipleFromGallery()) { processPickedImage(it, null) }
  val galleryLauncherFallback = rememberGetMultipleContentsLauncher { processPickedImage(it, null) }
  val filesLauncher = rememberGetContentLauncher { processPickedFile(it, null) }
  val recState: MutableState<RecordingState> = remember { mutableStateOf(RecordingState.NotStarted) }

  LaunchedEffect(attachmentOption.value) {
    when (attachmentOption.value) {
      AttachmentOption.TakePhoto -> {
        when (PackageManager.PERMISSION_GRANTED) {
          ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) -> {
            cameraLauncher.launchWithFallback()
          }
          else -> {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
          }
        }
        attachmentOption.value = null
      }
      AttachmentOption.PickImage -> {
        try {
          galleryLauncher.launch(0)
        } catch (e: ActivityNotFoundException) {
          galleryLauncherFallback.launch("image/*")
        }
        attachmentOption.value = null
      }
      AttachmentOption.PickFile -> {
        filesLauncher.launch("*/*")
        attachmentOption.value = null
      }
      else -> {}
    }
  }

  fun isSimplexLink(link: String): Boolean =
    link.startsWith("https://simplex.chat", true) || link.startsWith("http://simplex.chat", true)

  fun parseMessage(msg: String): String? {
    val parsedMsg = runBlocking { chatModel.controller.apiParseMarkdown(msg) }
    val link = parsedMsg?.firstOrNull { ft -> ft.format is Format.Uri && !cancelledLinks.contains(ft.text) && !isSimplexLink(ft.text) }
    return link?.text
  }

  fun loadLinkPreview(url: String, wait: Long? = null) {
    if (pendingLinkUrl.value == url) {
      composeState.value = composeState.value.copy(preview = ComposePreview.CLinkPreview(null))
      withApi {
        if (wait != null) delay(wait)
        val lp = getLinkPreview(url)
        if (lp != null && pendingLinkUrl.value == url) {
          composeState.value = composeState.value.copy(preview = ComposePreview.CLinkPreview(lp))
          pendingLinkUrl.value = null
        } else if (pendingLinkUrl.value == url) {
          composeState.value = composeState.value.copy(preview = ComposePreview.NoPreview)
          pendingLinkUrl.value = null
        }
      }
    }
  }

  fun showLinkPreview(s: String) {
    prevLinkUrl.value = linkUrl.value
    linkUrl.value = parseMessage(s)
    val url = linkUrl.value
    if (url != null) {
      if (url != composeState.value.linkPreview?.uri && url != pendingLinkUrl.value) {
        pendingLinkUrl.value = url
        loadLinkPreview(url, wait = if (prevLinkUrl.value == url) null else 1500L)
      }
    } else {
      composeState.value = composeState.value.copy(preview = ComposePreview.NoPreview)
    }
  }

  fun resetLinkPreview() {
    linkUrl.value = null
    prevLinkUrl.value = null
    pendingLinkUrl.value = null
    cancelledLinks.clear()
  }

  fun clearState(live: Boolean = false) {
    if (live) {
      composeState.value = composeState.value.copy(inProgress = false)
    } else {
      composeState.value = ComposeState(useLinkPreviews = useLinkPreviews)
      resetLinkPreview()
    }
    recState.value = RecordingState.NotStarted
    textStyle.value = smallFont
    chosenContent.value = emptyList()
    chosenAudio.value = null
    chosenFile.value = null
  }

  suspend fun send(cInfo: ChatInfo, mc: MsgContent, quoted: Long?, file: String? = null, live: Boolean = false): ChatItem? {
    val aChatItem = chatModel.controller.apiSendMessage(
      type = cInfo.chatType,
      id = cInfo.apiId,
      file = file,
      quotedItemId = quoted,
      mc = mc,
      live = live
    )
    if (aChatItem != null) chatModel.addChatItem(cInfo, aChatItem.chatItem)
    return aChatItem?.chatItem
  }



  suspend fun sendMessageAsync(text: String?, live: Boolean): ChatItem? {
    val cInfo = chat.chatInfo
    val cs = composeState.value
    var sent: ChatItem?
    val msgText = text ?: cs.message

    fun sending() {
      composeState.value = composeState.value.copy(inProgress = true)
    }

    fun checkLinkPreview(): MsgContent {
      return when (val composePreview = cs.preview) {
        is ComposePreview.CLinkPreview -> {
          val url = parseMessage(msgText)
          val lp = composePreview.linkPreview
          if (lp != null && url == lp.uri) {
            MsgContent.MCLink(msgText, preview = lp)
          } else {
            MsgContent.MCText(msgText)
          }
        }
        else -> MsgContent.MCText(msgText)
      }
    }

    fun updateMsgContent(msgContent: MsgContent): MsgContent {
      return when (msgContent) {
        is MsgContent.MCText -> checkLinkPreview()
        is MsgContent.MCLink -> checkLinkPreview()
        is MsgContent.MCImage -> MsgContent.MCImage(msgText, image = msgContent.image)
        is MsgContent.MCVoice -> MsgContent.MCVoice(msgText, duration = msgContent.duration)
        is MsgContent.MCFile -> MsgContent.MCFile(msgText)
        is MsgContent.MCUnknown -> MsgContent.MCUnknown(type = msgContent.type, text = msgText, json = msgContent.json)
      }
    }

    suspend fun updateMessage(ei: ChatItem, cInfo: ChatInfo, live: Boolean): ChatItem? {
      val oldMsgContent = ei.content.msgContent
      if (oldMsgContent != null) {
        val updatedItem = chatModel.controller.apiUpdateChatItem(
          type = cInfo.chatType,
          id = cInfo.apiId,
          itemId = ei.meta.itemId,
          mc = updateMsgContent(oldMsgContent),
          live = live
        )
        if (updatedItem != null) chatModel.upsertChatItem(cInfo, updatedItem.chatItem)
        return updatedItem?.chatItem
      }
      return null
    }

    val liveMessage = cs.liveMessage
    if (!live) {
      if (liveMessage != null) composeState.value = cs.copy(liveMessage = null)
      sending()
    }

    if (cs.contextItem is ComposeContextItem.EditingItem) {
      val ei = cs.contextItem.chatItem
      sent = updateMessage(ei, cInfo, live)
    } else if (liveMessage != null) {
      sent = updateMessage(liveMessage.chatItem, cInfo, live)
    } else {
      val msgs: ArrayList<MsgContent> = ArrayList()
      val files: ArrayList<String> = ArrayList()
      when (val preview = cs.preview) {
        ComposePreview.NoPreview -> msgs.add(MsgContent.MCText(msgText))
        is ComposePreview.CLinkPreview -> msgs.add(checkLinkPreview())
        is ComposePreview.ImagePreview -> {
          chosenContent.value.forEachIndexed { index, it ->
            val file = when (it) {
              is UploadContent.SimpleImage -> saveImage(context, it.uri)
              is UploadContent.AnimatedImage -> saveAnimImage(context, it.uri)
            }
            if (file != null) {
              files.add(file)
              msgs.add(MsgContent.MCImage(if (chosenContent.value.lastIndex == index) msgText else "", preview.images[index]))
            }
          }
        }
        is ComposePreview.VoicePreview -> {
          val chosenAudioVal = chosenAudio.value
          if (chosenAudioVal != null) {
            val file = chosenAudioVal.first.toFile().name
            files.add((file))
            chatModel.filesToDelete.remove(chosenAudioVal.first.toFile())
            AudioPlayer.stop(chosenAudioVal.first.toFile().absolutePath)
            msgs.add(MsgContent.MCVoice(if (msgs.isEmpty()) msgText else "", chosenAudioVal.second / 1000))
          }
        }
        is ComposePreview.FilePreview -> {
          val chosenFileVal = chosenFile.value
          if (chosenFileVal != null) {
            val file = saveFileFromUri(context, chosenFileVal)
            if (file != null) {
              files.add((file))
              msgs.add(MsgContent.MCFile(if (msgs.isEmpty()) msgText else ""))
            }
          }
        }
      }
      val quotedItemId: Long? = when (cs.contextItem) {
        is ComposeContextItem.QuotedItem -> cs.contextItem.chatItem.id
        else -> null
      }
      sent = null
      msgs.forEachIndexed { index, content ->
        if (index > 0) delay(100)
        sent = send(cInfo, content, if (index == 0) quotedItemId else null, files.getOrNull(index),
          if (content !is MsgContent.MCVoice && index == msgs.lastIndex) live else false
        )
      }
      if (sent == null && chosenContent.value.isNotEmpty()) {
        sent = send(cInfo, MsgContent.MCText(msgText), quotedItemId, null, live)
      }
    }
    clearState(live)
    return sent
  }

  fun sendMessage() {
    withBGApi {
      sendMessageAsync(null, false)
    }
  }

  fun onMessageChange(s: String) {
    composeState.value = composeState.value.copy(message = s)
    if (isShortEmoji(s)) {
      textStyle.value = if (s.codePoints().count() < 4) largeEmojiFont else mediumEmojiFont
    } else {
      textStyle.value = smallFont
      if (composeState.value.linkPreviewAllowed) {
        if (s.isNotEmpty()) showLinkPreview(s)
        else resetLinkPreview()
      }
    }
  }

  fun onAudioAdded(filePath: String, durationMs: Int, finished: Boolean) {
    val file = File(filePath)
    chosenAudio.value = file.toUri() to durationMs
    chatModel.filesToDelete.add(file)
    composeState.value = composeState.value.copy(preview = ComposePreview.VoicePreview(filePath, durationMs, finished))
  }

  fun allowVoiceToContact() {
    val contact = (chat.chatInfo as ChatInfo.Direct?)?.contact ?: return
    withApi {
      chatModel.controller.allowFeatureToContact(contact, ChatFeature.Voice)
    }
  }

  fun cancelLinkPreview() {
    val uri = composeState.value.linkPreview?.uri
    if (uri != null) {
      cancelledLinks.add(uri)
    }
    pendingLinkUrl.value = null
    composeState.value = composeState.value.copy(preview = ComposePreview.NoPreview)
  }

  fun cancelImages() {
    composeState.value = composeState.value.copy(preview = ComposePreview.NoPreview)
    chosenContent.value = emptyList()
  }

  fun cancelVoice() {
    val filePath = recState.value.filePathNullable
    recState.value = RecordingState.NotStarted
    composeState.value = composeState.value.copy(preview = ComposePreview.NoPreview)
    withBGApi {
      RecorderNative.stopRecording?.invoke()
      AudioPlayer.stop(filePath)
      filePath?.let { File(it).delete() }
    }
    chosenAudio.value = null
  }

  fun cancelFile() {
    composeState.value = composeState.value.copy(preview = ComposePreview.NoPreview)
    chosenFile.value = null
  }

  fun truncateToWords(s: String): String {
    var acc = ""
    val word = StringBuilder()
    for (c in s) {
      if (c.isLetter() || c.isDigit()) {
        word.append(c)
      } else {
        acc = acc + word.toString() + c
        word.clear()
      }
    }
    return acc
  }

  suspend fun sendLiveMessage() {
    val typedMsg = composeState.value.message
    val sentMsg = truncateToWords(typedMsg)
    if (composeState.value.liveMessage == null) {
      val ci = sendMessageAsync(sentMsg, live = true)
      if (ci != null) {
        composeState.value = composeState.value.copy(liveMessage = LiveMessage(ci, typedMsg = typedMsg, sentMsg = sentMsg))
      }
    }
  }

  fun liveMessageToSend(lm: LiveMessage, t: String): String? {
    val s = if (t != lm.typedMsg) truncateToWords(t) else t
    return if (s != lm.sentMsg) s else null
  }

  suspend fun updateLiveMessage() {
    val typedMsg = composeState.value.message
    val liveMessage = composeState.value.liveMessage
    if (liveMessage != null) {
      val sentMsg = liveMessageToSend(liveMessage, typedMsg)
      if (sentMsg != null) {
        val ci = sendMessageAsync(sentMsg, live = true)
        if (ci != null) {
          composeState.value = composeState.value.copy(liveMessage = LiveMessage(ci, typedMsg = typedMsg, sentMsg = sentMsg))
        }
      } else if (liveMessage.typedMsg != typedMsg) {
        composeState.value = composeState.value.copy(liveMessage = liveMessage.copy(typedMsg = typedMsg))
      }
    }
  }

  @Composable
  fun previewView() {
    when (val preview = composeState.value.preview) {
      ComposePreview.NoPreview -> {}
      is ComposePreview.CLinkPreview -> ComposeLinkView(preview.linkPreview, ::cancelLinkPreview)
      is ComposePreview.ImagePreview -> ComposeImageView(
        preview.images,
        ::cancelImages,
        cancelEnabled = !composeState.value.editing
      )
      is ComposePreview.VoicePreview -> ComposeVoiceView(
        preview.voice,
        preview.durationMs,
        preview.finished,
        cancelEnabled = !composeState.value.editing,
        ::cancelVoice
      )
      is ComposePreview.FilePreview -> ComposeFileView(
        preview.fileName,
        ::cancelFile,
        cancelEnabled = !composeState.value.editing
      )
    }
  }

  @Composable
  fun contextItemView() {
    when (val contextItem = composeState.value.contextItem) {
      ComposeContextItem.NoContextItem -> {}
      is ComposeContextItem.QuotedItem -> ContextItemView(contextItem.chatItem, Icons.Outlined.Reply) {
        composeState.value = composeState.value.copy(contextItem = ComposeContextItem.NoContextItem)
      }
      is ComposeContextItem.EditingItem -> ContextItemView(contextItem.chatItem, Icons.Filled.Edit) {
        clearState()
      }
    }
  }

  LaunchedEffect(chatModel.sharedContent.value) {
    // Important. If it's null, don't do anything, chat is not closed yet but will be after a moment
    if (chatModel.chatId.value == null) return@LaunchedEffect

    when (val shared = chatModel.sharedContent.value) {
      is SharedContent.Text -> onMessageChange(shared.text)
      is SharedContent.Images -> processPickedImage(shared.uris, shared.text)
      is SharedContent.File -> processPickedFile(shared.uri, shared.text)
      null -> {}
    }
    chatModel.sharedContent.value = null
  }

  Column {
    contextItemView()
    when {
      composeState.value.editing && composeState.value.preview is ComposePreview.VoicePreview -> {}
      composeState.value.editing && composeState.value.preview is ComposePreview.FilePreview -> {}
      else -> previewView()
    }
    Row(
      modifier = Modifier.padding(end = 8.dp),
      verticalAlignment = Alignment.Bottom,
    ) {
      IconButton(showChooseAttachment, enabled = !composeState.value.attachmentDisabled) {
        Icon(
          Icons.Filled.AttachFile,
          contentDescription = stringResource(R.string.attach),
          tint = if (!composeState.value.attachmentDisabled) MaterialTheme.colors.primary else HighOrLowlight,
          modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
        )
      }
      val allowedVoiceByPrefs = remember(chat.chatInfo) { chat.chatInfo.featureEnabled(ChatFeature.Voice) }
      LaunchedEffect(allowedVoiceByPrefs) {
        if (!allowedVoiceByPrefs && chosenAudio.value != null) {
          // Voice was disabled right when this user records it, just cancel it
          cancelVoice()
        }
      }
      val needToAllowVoiceToContact = remember(chat.chatInfo) {
        chat.chatInfo is ChatInfo.Direct && with(chat.chatInfo.contact.mergedPreferences.voice) {
          ((userPreference as? ContactUserPref.User)?.preference?.allow == FeatureAllowed.NO || (userPreference as? ContactUserPref.Contact)?.preference?.allow == FeatureAllowed.NO) &&
              contactPreference.allow == FeatureAllowed.YES
        }
      }
      LaunchedEffect(Unit) {
        snapshotFlow { recState.value }
          .distinctUntilChanged()
          .collect {
            when(it) {
              is RecordingState.Started -> onAudioAdded(it.filePath, it.progressMs, false)
              is RecordingState.Finished -> onAudioAdded(it.filePath, it.durationMs, true)
              is RecordingState.NotStarted -> {}
            }
          }
      }

      val activity = LocalContext.current as Activity
      DisposableEffect(Unit) {
        val orientation = activity.resources.configuration.orientation
        onDispose {
          if (orientation == activity.resources.configuration.orientation && composeState.value.liveMessage != null) {
            sendMessage()
            resetLinkPreview()
          }
        }
      }

      SendMsgView(
        composeState,
        showVoiceRecordIcon = true,
        recState,
        chat.chatInfo is ChatInfo.Direct,
        liveMessageAlertShown = chatModel.controller.appPrefs.liveMessageAlertShown,
        needToAllowVoiceToContact,
        allowedVoiceByPrefs,
        allowVoiceToContact = ::allowVoiceToContact,
        sendMessage = {
          sendMessage()
          resetLinkPreview()
        },
        sendLiveMessage = ::sendLiveMessage,
        updateLiveMessage = ::updateLiveMessage,
        onMessageChange = ::onMessageChange,
        textStyle = textStyle
      )
    }
  }
}

class PickFromGallery: ActivityResultContract<Int, Uri?>() {
  override fun createIntent(context: Context, input: Int) =
    Intent(Intent.ACTION_PICK, MediaStore.Images.Media.INTERNAL_CONTENT_URI).apply {
      type = "image/*"
    }

  override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
}

class PickMultipleFromGallery: ActivityResultContract<Int, List<Uri>>() {
  override fun createIntent(context: Context, input: Int) =
    Intent(Intent.ACTION_PICK, MediaStore.Images.Media.INTERNAL_CONTENT_URI).apply {
      putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
      type = "image/*"
    }

  override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> =
    if (intent?.data != null)
      listOf(intent.data!!)
    else if (intent?.clipData != null)
      with(intent.clipData!!) {
        val uris = ArrayList<Uri>()
        for (i in 0 until kotlin.math.min(itemCount, 10)) {
          val uri = getItemAt(i).uri
          if (uri != null) uris.add(uri)
        }
        if (itemCount > 10) {
          AlertManager.shared.showAlertMsg(R.string.images_limit_title, R.string.images_limit_desc)
        }
        uris
      }
    else
      emptyList()
}
