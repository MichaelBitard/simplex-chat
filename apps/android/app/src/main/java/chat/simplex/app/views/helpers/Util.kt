package chat.simplex.app.views.helpers

import android.content.Context
import android.content.res.Resources
import android.graphics.*
import android.graphics.Typeface
import android.net.Uri
import android.os.FileUtils
import android.provider.OpenableColumns
import android.text.Spanned
import android.text.SpannedString
import android.text.style.*
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import androidx.annotation.StringRes
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.*
import androidx.core.content.FileProvider
import androidx.core.text.HtmlCompat
import chat.simplex.app.*
import chat.simplex.app.model.CIFile
import chat.simplex.app.model.json
import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

fun withApi(action: suspend CoroutineScope.() -> Unit): Job = withScope(GlobalScope, action)

fun withScope(scope: CoroutineScope, action: suspend CoroutineScope.() -> Unit): Job =
  scope.launch { withContext(Dispatchers.Main, action) }

fun withBGApi(action: suspend CoroutineScope.() -> Unit): Job =
  CoroutineScope(Dispatchers.Default).launch(block = action)

enum class KeyboardState {
  Opened, Closed
}

@Composable
fun getKeyboardState(): State<KeyboardState> {
  val keyboardState = remember { mutableStateOf(KeyboardState.Closed) }
  val view = LocalView.current
  DisposableEffect(view) {
    val onGlobalListener = ViewTreeObserver.OnGlobalLayoutListener {
      val rect = Rect()
      view.getWindowVisibleDisplayFrame(rect)
      val screenHeight = view.rootView.height
      val keypadHeight = screenHeight - rect.bottom
      keyboardState.value = if (keypadHeight > screenHeight * 0.15) {
        KeyboardState.Opened
      } else {
        KeyboardState.Closed
      }
    }
    view.viewTreeObserver.addOnGlobalLayoutListener(onGlobalListener)

    onDispose {
      view.viewTreeObserver.removeOnGlobalLayoutListener(onGlobalListener)
    }
  }

  return keyboardState
}

fun hideKeyboard(view: View) =
  (SimplexApp.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)

// Resource to annotated string from
// https://stackoverflow.com/questions/68549248/android-jetpack-compose-how-to-show-styled-text-from-string-resources
fun generalGetString(id: Int): String {
  // prefer stringResource in Composable items to retain preview abilities
  return SimplexApp.context.getString(id)
}

@Composable
@ReadOnlyComposable
private fun resources(): Resources {
  LocalConfiguration.current
  return LocalContext.current.resources
}

fun Spanned.toHtmlWithoutParagraphs(): String {
  return HtmlCompat.toHtml(this, HtmlCompat.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)
    .substringAfter("<p dir=\"ltr\">").substringBeforeLast("</p>")
}

fun Resources.getText(@StringRes id: Int, vararg args: Any): CharSequence {
  val escapedArgs = args.map {
    if (it is Spanned) it.toHtmlWithoutParagraphs() else it
  }.toTypedArray()
  val resource = SpannedString(getText(id))
  val htmlResource = resource.toHtmlWithoutParagraphs()
  val formattedHtml = String.format(htmlResource, *escapedArgs)
  return HtmlCompat.fromHtml(formattedHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
}

@Composable
fun annotatedStringResource(@StringRes id: Int): AnnotatedString {
  val resources = resources()
  val density = LocalDensity.current
  return remember(id) {
    val text = resources.getText(id)
    spannableStringToAnnotatedString(text, density)
  }
}

private fun spannableStringToAnnotatedString(
  text: CharSequence,
  density: Density,
): AnnotatedString {
  return if (text is Spanned) {
    with(density) {
      buildAnnotatedString {
        append((text.toString()))
        text.getSpans(0, text.length, Any::class.java).forEach {
          val start = text.getSpanStart(it)
          val end = text.getSpanEnd(it)
          when (it) {
            is StyleSpan -> when (it.style) {
              Typeface.NORMAL -> addStyle(
                SpanStyle(
                  fontWeight = FontWeight.Normal,
                  fontStyle = FontStyle.Normal,
                ),
                start,
                end
              )
              Typeface.BOLD -> addStyle(
                SpanStyle(
                  fontWeight = FontWeight.Bold,
                  fontStyle = FontStyle.Normal
                ),
                start,
                end
              )
              Typeface.ITALIC -> addStyle(
                SpanStyle(
                  fontWeight = FontWeight.Normal,
                  fontStyle = FontStyle.Italic
                ),
                start,
                end
              )
              Typeface.BOLD_ITALIC -> addStyle(
                SpanStyle(
                  fontWeight = FontWeight.Bold,
                  fontStyle = FontStyle.Italic
                ),
                start,
                end
              )
            }
            is TypefaceSpan -> addStyle(
              SpanStyle(
                fontFamily = when (it.family) {
                  FontFamily.SansSerif.name -> FontFamily.SansSerif
                  FontFamily.Serif.name -> FontFamily.Serif
                  FontFamily.Monospace.name -> FontFamily.Monospace
                  FontFamily.Cursive.name -> FontFamily.Cursive
                  else -> FontFamily.Default
                }
              ),
              start,
              end
            )
            is AbsoluteSizeSpan -> addStyle(
              SpanStyle(fontSize = if (it.dip) it.size.dp.toSp() else it.size.toSp()),
              start,
              end
            )
            is RelativeSizeSpan -> addStyle(
              SpanStyle(fontSize = it.sizeChange.em),
              start,
              end
            )
            is StrikethroughSpan -> addStyle(
              SpanStyle(textDecoration = TextDecoration.LineThrough),
              start,
              end
            )
            is UnderlineSpan -> addStyle(
              SpanStyle(textDecoration = TextDecoration.Underline),
              start,
              end
            )
            is SuperscriptSpan -> addStyle(
              SpanStyle(baselineShift = BaselineShift.Superscript),
              start,
              end
            )
            is SubscriptSpan -> addStyle(
              SpanStyle(baselineShift = BaselineShift.Subscript),
              start,
              end
            )
            is ForegroundColorSpan -> addStyle(
              SpanStyle(color = Color(it.foregroundColor)),
              start,
              end
            )
            else -> addStyle(SpanStyle(color = Color.White), start, end)
          }
        }
      }
    }
  } else {
    AnnotatedString(text.toString())
  }
}

// maximum image file size to be auto-accepted
const val MAX_IMAGE_SIZE: Long = 236700
const val MAX_IMAGE_SIZE_AUTO_RCV: Long = MAX_IMAGE_SIZE * 2
const val MAX_VOICE_SIZE_AUTO_RCV: Long = MAX_IMAGE_SIZE

const val MAX_VOICE_SIZE_FOR_SENDING: Long = 94680 // 6 chunks * 15780 bytes per chunk
const val MAX_VOICE_MILLIS_FOR_SENDING: Int = 43_000

const val MAX_FILE_SIZE: Long = 8000000

fun getFilesDirectory(context: Context): String {
  return context.filesDir.toString()
}

fun getAppFilesDirectory(context: Context): String {
  return "${getFilesDirectory(context)}/app_files"
}

fun getAppFilePath(context: Context, fileName: String): String {
  return "${getAppFilesDirectory(context)}/$fileName"
}

fun getLoadedFilePath(context: Context, file: CIFile?): String? {
  return if (file?.filePath != null && file.loaded) {
    val filePath = getAppFilePath(context, file.filePath)
    if (File(filePath).exists()) filePath else null
  } else {
    null
  }
}

// https://developer.android.com/training/data-storage/shared/documents-files#bitmap
fun getLoadedImage(context: Context, file: CIFile?): Bitmap? {
  val filePath = getLoadedFilePath(context, file)
  return if (filePath != null) {
    try {
      val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.provider", File(filePath))
      val parcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
      val fileDescriptor = parcelFileDescriptor?.fileDescriptor
      val image = decodeSampledBitmapFromFileDescriptor(fileDescriptor, 1000, 1000)
      parcelFileDescriptor?.close()
      image
    } catch (e: Exception) {
      null
    }
  } else {
    null
  }
}

// https://developer.android.com/topic/performance/graphics/load-bitmap#load-bitmap
private fun decodeSampledBitmapFromFileDescriptor(fileDescriptor: FileDescriptor?, reqWidth: Int, reqHeight: Int): Bitmap {
  // First decode with inJustDecodeBounds=true to check dimensions
  return BitmapFactory.Options().run {
    inJustDecodeBounds = true
    BitmapFactory.decodeFileDescriptor(fileDescriptor, null, this)
    // Calculate inSampleSize
    inSampleSize = calculateInSampleSize(this, reqWidth, reqHeight)
    // Decode bitmap with inSampleSize set
    inJustDecodeBounds = false

    BitmapFactory.decodeFileDescriptor(fileDescriptor, null, this)
  }
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
  // Raw height and width of image
  val (height: Int, width: Int) = options.run { outHeight to outWidth }
  var inSampleSize = 1

  if (height > reqHeight || width > reqWidth) {
    val halfHeight: Int = height / 2
    val halfWidth: Int = width / 2
    // Calculate the largest inSampleSize value that is a power of 2 and keeps both
    // height and width larger than the requested height and width.
    while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
      inSampleSize *= 2
    }
  }

  return inSampleSize
}

fun getFileName(context: Context, uri: Uri): String? {
  return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
    cursor.moveToFirst()
    cursor.getString(nameIndex)
  }
}

fun getFileSize(context: Context, uri: Uri): Long? {
  return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
    cursor.moveToFirst()
    cursor.getLong(sizeIndex)
  }
}

fun saveImage(context: Context, uri: Uri): String? {
  val source = ImageDecoder.createSource(SimplexApp.context.contentResolver, uri)
  val bitmap = ImageDecoder.decodeBitmap(source)
  return saveImage(context, bitmap)
}

fun saveImage(context: Context, image: Bitmap): String? {
  return try {
    val ext = if (image.hasAlpha()) "png" else "jpg"
    val dataResized = resizeImageToDataSize(image, ext == "png", maxDataSize = MAX_IMAGE_SIZE)
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val fileToSave = uniqueCombine(context, "IMG_${timestamp}.$ext")
    val file = File(getAppFilePath(context, fileToSave))
    val output = FileOutputStream(file)
    dataResized.writeTo(output)
    output.flush()
    output.close()
    fileToSave
  } catch (e: Exception) {
    Log.e(chat.simplex.app.TAG, "Util.kt saveImage error: ${e.message}")
    null
  }
}

fun saveAnimImage(context: Context, uri: Uri): String? {
  return try {
    val filename = getFileName(context, uri)?.lowercase()
    var ext = when {
      // remove everything but extension
      filename?.contains(".") == true -> filename.replaceBeforeLast('.', "").replace(".", "")
      else -> "gif"
    }
    // Just in case the image has a strange extension
    if (ext.length < 3 || ext.length > 4) ext = "gif"
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val fileToSave = uniqueCombine(context, "IMG_${timestamp}.$ext")
    val file = File(getAppFilePath(context, fileToSave))
    val output = FileOutputStream(file)
    context.contentResolver.openInputStream(uri)!!.use { input ->
      output.use { output ->
        input.copyTo(output)
      }
    }
    fileToSave
  } catch (e: Exception) {
    Log.e(chat.simplex.app.TAG, "Util.kt saveAnimImage error: ${e.message}")
    null
  }
}

fun saveFileFromUri(context: Context, uri: Uri): String? {
  return try {
    val inputStream = context.contentResolver.openInputStream(uri)
    val fileToSave = getFileName(context, uri)
    if (inputStream != null && fileToSave != null) {
      val destFileName = uniqueCombine(context, fileToSave)
      val destFile = File(getAppFilePath(context, destFileName))
      FileUtils.copy(inputStream, FileOutputStream(destFile))
      destFileName
    } else {
      Log.e(chat.simplex.app.TAG, "Util.kt saveFileFromUri null inputStream")
      null
    }
  } catch (e: Exception) {
    Log.e(chat.simplex.app.TAG, "Util.kt saveFileFromUri error: ${e.message}")
    null
  }
}

fun uniqueCombine(context: Context, fileName: String): String {
  fun tryCombine(fileName: String, n: Int): String {
    val name = File(fileName).nameWithoutExtension
    val ext = File(fileName).extension
    val suffix = if (n == 0) "" else "_$n"
    val f = "$name$suffix.$ext"
    return if (File(getAppFilePath(context, f)).exists()) tryCombine(fileName, n + 1) else f
  }
  return tryCombine(fileName, 0)
}

fun formatBytes(bytes: Long): String {
  if (bytes == 0.toLong()) {
    return "0 bytes"
  }
  val bytesDouble = bytes.toDouble()
  val k = 1000.toDouble()
  val units = arrayOf("bytes", "KB", "MB", "GB", "TB", "PB", "EB", "ZB", "YB")
  val i = kotlin.math.floor(log2(bytesDouble) / log2(k))
  val size = bytesDouble / k.pow(i)
  val unit = units[i.toInt()]

  return if (i <= 1) {
    String.format("%.0f %s", size, unit)
  } else {
    String.format("%.2f %s", size, unit)
  }
}

fun removeFile(context: Context, fileName: String): Boolean {
  val file = File(getAppFilePath(context, fileName))
  val fileDeleted = file.delete()
  if (!fileDeleted) {
    Log.e(chat.simplex.app.TAG, "Util.kt removeFile error")
  }
  return fileDeleted
}

fun deleteAppFiles(context: Context) {
  val dir = File(getAppFilesDirectory(context))
  try {
    dir.list()?.forEach {
      removeFile(context, it)
    }
  } catch (e: java.lang.Exception) {
    Log.e(TAG, "Util deleteAppFiles error: ${e.stackTraceToString()}")
  }
}

fun directoryFileCountAndSize(dir: String): Pair<Int, Long> { // count, size in bytes
  var fileCount = 0
  var bytes = 0L
  try {
    File(dir).listFiles()?.forEach {
      fileCount++
      bytes += it.length()
    }
  } catch (e: java.lang.Exception) {
    Log.e(TAG, "Util directoryFileCountAndSize error: ${e.stackTraceToString()}")
  }
  return fileCount to bytes
}

fun Color.darker(factor: Float = 0.1f): Color =
  Color(max(red * (1 - factor), 0f), max(green * (1 - factor), 0f), max(blue * (1 - factor), 0f), alpha)

fun ByteArray.toBase64String() = Base64.encodeToString(this, Base64.DEFAULT)

fun String.toByteArrayFromBase64() = Base64.decode(this, Base64.DEFAULT)

val LongRange.Companion.saver
  get() = Saver<MutableState<LongRange>, Pair<Long, Long>>(
    save = { it.value.first to it.value.last },
    restore = { mutableStateOf(it.first..it.second) }
    )

/* Make sure that T class has @Serializable annotation */
inline fun <reified T> serializableSaver(): Saver<T, *> = Saver(
    save = { json.encodeToString(it) },
    restore = { json.decodeFromString(it) }
  )
