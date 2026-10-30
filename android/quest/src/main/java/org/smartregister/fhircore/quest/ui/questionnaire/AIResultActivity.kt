package org.smartregister.fhircore.quest.ui.questionnaire

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bumptech.glide.Glide
import org.smartregister.fhircore.engine.ui.theme.LighterBlue
import org.smartregister.fhircore.engine.ui.theme.PrimaryColor
import org.smartregister.fhircore.engine.util.extension.applyWindowInsetListener
import org.smartregister.fhircore.ai.Outcome
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.util.PostHogAnalytics
import org.smartregister.fhircore.quest.util.ScreeningTimer
import org.smartregister.fhircore.quest.theme.Colors
import org.smartregister.fhircore.quest.theme.Colors.WHITE
import org.smartregister.fhircore.quest.theme.body18Medium
import org.smartregister.fhircore.quest.ui.register.customui.ZoomableImageView

class AIResultActivity : ComponentActivity() {

    private val previewCacheDir by lazy {
        File(cacheDir, "ai_result_preview").also { it.mkdirs() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val outcome =
            intent.getStringExtra(EXTRA_OUTCOME)?.let { name -> Outcome.values().firstOrNull { it.name == name } }
                ?: if (intent.getBooleanExtra(EXTRA_IS_SUSPICIOUS, false)) Outcome.POSITIVE else Outcome.NEGATIVE
        val isSuspicious = outcome == Outcome.POSITIVE
        val flaggedImages = intent.getStringArrayListExtra(EXTRA_IMAGES) ?: emptyList<String>()
        val titleOverride = intent.getStringExtra(EXTRA_TITLE)
        val descriptionOverride = intent.getStringExtra(EXTRA_DESCRIPTION)
        val screeningId = intent.getStringExtra(PostHogAnalytics.Props.SCREENING_ID)

        ScreeningTimer.markStep(screeningId, "ai_result_viewed")
        PostHogAnalytics.capture(
            PostHogAnalytics.Events.AI_RESULT_VIEWED,
            mapOf(
                PostHogAnalytics.Props.IS_SUSPICIOUS to isSuspicious,
                PostHogAnalytics.Props.AI_OUTCOME to outcome.name,
                PostHogAnalytics.Props.SCREENING_ID to screeningId,
            ),
        )

        // Copy images to a private cache dir off the main thread. Sync worker only deletes after a
        // successful network upload + server verification, so this copy always wins the race.
        // Copies are cleaned up in onDestroy.
        val showsImages = outcome != Outcome.NEGATIVE
        var displayImages by mutableStateOf(if (showsImages) flaggedImages else emptyList<String>())
        if (showsImages && flaggedImages.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val cached = flaggedImages.mapNotNull { copyToPreviewCache(it) }
                withContext(Dispatchers.Main) { displayImages = cached }
            }
        }

        setContent {
            MaterialTheme {
                AIResultScreen(
                    outcome = outcome,
                    suspiciousImages = displayImages,
                    titleOverride = titleOverride,
                    descriptionOverride = descriptionOverride,
                    onClose = {
                        intent.putExtra("refer_case", false)
                        setResult(RESULT_OK, intent)
                        finish()
                    },
                    onRefer = {
                        PostHogAnalytics.capture(
                            PostHogAnalytics.Events.AI_REFER_CASE,
                            mapOf(
                                PostHogAnalytics.Props.IS_SUSPICIOUS to isSuspicious,
                                PostHogAnalytics.Props.AI_OUTCOME to outcome.name,
                                PostHogAnalytics.Props.SCREENING_ID to screeningId,
                            ),
                        )
                        intent.putExtra("refer_case", true)
                        setResult(RESULT_OK, intent)
                        finish()
                    }
                )
            }
        }

        // Preserve the pre-edge-to-edge look now that Android 16 (targetSdk 36) enforces
        // edge-to-edge and no longer honors windowOptOutEdgeToEdgeEnforcement.
        applyWindowInsetListener()
    }

    override fun onDestroy() {
        super.onDestroy()
        previewCacheDir.deleteRecursively()
    }

    companion object {
        /** [Outcome] name of the case. */
        const val EXTRA_OUTCOME = "aiOutcome"

        /** Kept for callers that only know suspicious / non-suspicious. */
        const val EXTRA_IS_SUSPICIOUS = "isSuspicious"

        /** Photos that explain the outcome (flagged, or not assessable). */
        const val EXTRA_IMAGES = "suspiciousImages"

        /** Optional result-screen title from the model's class descriptor. */
        const val EXTRA_TITLE = "aiResultTitle"

        /** Optional result-screen explanation from the model's class descriptor. */
        const val EXTRA_DESCRIPTION = "aiResultDescription"
    }

    private fun copyToPreviewCache(sourcePath: String): String? {
        val path = sourcePath.trim()
        if (path.isEmpty()) return null
        return try {
            val destFile = File(previewCacheDir, "${path.hashCode()}.img")
            if (destFile.exists() && destFile.length() > 0) return destFile.absolutePath

            val uri = Uri.parse(path)
            val inputStream = when (uri.scheme?.lowercase()) {
                "content" -> contentResolver.openInputStream(uri)
                "file" -> File(uri.path ?: return null).takeIf { it.isFile }?.inputStream()
                else -> File(path).takeIf { it.isFile }?.inputStream()
                    ?: File(filesDir, path).takeIf { it.isFile }?.inputStream()
                    ?: File(cacheDir, path).takeIf { it.isFile }?.inputStream()
            } ?: return null

            inputStream.use { input -> destFile.outputStream().use { input.copyTo(it) } }
            destFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIResultScreen(
    outcome: Outcome,
    suspiciousImages: List<String>,
    onClose: () -> Unit,
    onRefer: () -> Unit,
    titleOverride: String? = null,
    descriptionOverride: String? = null,
) {
    val isSuspicious = outcome == Outcome.POSITIVE
    val isInconclusive = outcome == Outcome.INCONCLUSIVE
    val context = LocalContext.current
    // The case is already saved when this screen opens, so system back must report a result
    // (RESULT_OK, refer_case=false) like the Done button instead of returning the user to the
    // already-submitted form, where submitting again would register a duplicate case.
    BackHandler(onBack = onClose)
    val backgroundColor =
        when (outcome) {
            Outcome.POSITIVE -> Colors.CORNSILK
            Outcome.NEGATIVE -> LighterBlue
            Outcome.INCONCLUSIVE -> InconclusiveBackground
        }
    val title = if (isSuspicious) stringResource(R.string.add_patient) else stringResource(R.string.ai_result_title)
    var fullScreenImageUrl by remember { mutableStateOf<String?>(null) }
    val displayableSuspiciousImages = remember(context, suspiciousImages) {
        suspiciousImages
            .map { it.trim() }
            .filter { it.isNotEmpty() && resolveImageLoadModel(context, it) != null }
            .distinct()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        // Top App Bar
        TopAppBar(
            title = {
                Text(
                    text = title,
                    fontSize = 20.sp,
                    style = body18Medium().copy(color = WHITE)
                )
            },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_arrow_back),
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = PrimaryColor
            )
        )

        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Main Illustration and Text
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 48.dp)
            ) {
                // Illustration
                Box(
                    modifier = Modifier.size(124.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isInconclusive) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_alert_triangle),
                            contentDescription = "Result Illustration",
                            modifier = Modifier.size(96.dp)
                        )
                    } else {
                        Image(
                            painter = painterResource(
                                id = if (isSuspicious) R.drawable.positive_sign else R.drawable.negative_sign
                            ),
                            contentDescription = "Result Illustration",
                            modifier = Modifier.size(120.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = titleOverride ?: stringResource(
                        when (outcome) {
                            Outcome.POSITIVE -> R.string.suspicious_result
                            Outcome.NEGATIVE -> R.string.nonsuspicious_result
                            Outcome.INCONCLUSIVE -> R.string.inconclusive_result
                        }
                    ),
                    style = body18Medium().copy(
                        color = Color(0xFF3D392E),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = descriptionOverride ?: stringResource(
                        when (outcome) {
                            Outcome.POSITIVE -> R.string.suspicious_result_desc
                            Outcome.NEGATIVE -> R.string.nonsuspicious_result_desc
                            Outcome.INCONCLUSIVE -> R.string.inconclusive_result_desc
                        }
                    ),
                    style = body18Medium().copy(
                        color = Color(0xAB3D392E),
                        fontWeight = FontWeight.Normal,
                        lineHeight = 24.sp,
                        fontSize = 18.sp,
                    ),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.width(264.dp).height(64.dp)
                )
            }

            // AI-DETECTED LESIONS Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp)
            ) {
                Text(
                    text = stringResource(
                        if (isInconclusive) R.string.ai_inconclusive_photos else R.string.ai_detected_lesions
                    ),
                    style = body18Medium().copy(
                        color = Color(0xAB2F363D),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.8.sp
                    ),
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                if (!isNegative(outcome) && displayableSuspiciousImages.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(displayableSuspiciousImages, key = { it }) { imagePath ->
                            Box(
                                modifier = Modifier
                                    .size(width = 264.dp, height = 237.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White)
                                    .clickable { fullScreenImageUrl = imagePath },
                                contentAlignment = Alignment.Center
                            ) {
                                LocalGlideImage(
                                    path = imagePath,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                } else if (isNegative(outcome) || isInconclusive) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.64f)),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = stringResource(
                                if (isInconclusive) R.string.no_photos_assessed else R.string.no_lesions_found
                            ),
                            modifier = Modifier.padding(start = 16.dp),
                            style = body18Medium().copy(
                                color = Color(0xFF3D392E),
                                fontSize = 18.sp
                            )
                        )
                    }
                } else {
                    // Suspicious but no images found
                    Text(
                        text = "No images available",
                        color = Color.Gray,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }

            // Bottom Buttons
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                if (isSuspicious) {
                    Button(
                        onClick = onClose,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryColor
                        ),
                        shape = RoundedCornerShape(2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.done_upper),
                            style = body18Medium().copy(
                                color = WHITE,
                                letterSpacing = 2.25.sp,
                                fontSize = 16.sp
                            )
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedButton(
                            onClick = onRefer,
                            modifier = Modifier
                                .weight(1f)
                                .background(Color.White)
                                .fillMaxHeight(),
                            shape = RoundedCornerShape(2.dp),
                            border = BorderStroke(1.dp, PrimaryColor)
                        ) {
                            Text(
                                text = stringResource(
                                    if (isInconclusive) R.string.refer_case_upper else R.string.refer_case
                                ),
                                style = body18Medium().copy(
                                    color = PrimaryColor,
                                    letterSpacing = 1.25.sp,
                                    fontSize = 16.sp
                                )
                            )
                        }
                        Button(
                            onClick = onClose,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimaryColor
                            ),
                            shape = RoundedCornerShape(2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.done_upper),
                                style = body18Medium().copy(
                                    color = WHITE,
                                    letterSpacing = 1.25.sp,
                                    fontSize = 16.sp
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    // Full Screen Overlay
    if (fullScreenImageUrl != null) {
        FullscreenImageOverlay(
            path = fullScreenImageUrl!!,
            onClose = { fullScreenImageUrl = null }
        )
    }
}

@Composable
fun LocalGlideImage(
    path: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageModel = remember(context, path) { resolveImageLoadModel(context, path) }
    AndroidView(
        factory = { ctx ->
            androidx.appcompat.widget.AppCompatImageView(ctx).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            }
        },
        modifier = modifier,
        update = { view ->
            if (imageModel == null) {
                Glide.with(view.context).clear(view)
                view.setImageResource(android.R.drawable.ic_menu_gallery)
            } else {
                Glide.with(view.context)
                    .load(imageModel)
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .error(android.R.drawable.ic_menu_gallery)
                    .into(view)
            }
        }
    )
}

@Composable
fun FullscreenImageOverlay(
    path: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val imageModel = remember(context, path) { resolveImageLoadModel(context, path) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        BackHandler(onBack = onClose)
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Main Zoomable Image
            AndroidView(
                factory = { ctx ->
                    ZoomableImageView(ctx)
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    Glide.with(view.context)
                        .load(imageModel)
                        .error(android.R.drawable.ic_menu_delete)
                        .into(view)
                }
            )

            // Close Button as per Figma
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 40.dp, end = 16.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f))
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_cancel),
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

private fun resolveImageLoadModel(context: Context, rawPath: String): Any? {
    val path = rawPath.trim()
    if (path.isEmpty()) return null

    val uri = Uri.parse(path)
    when (uri.scheme?.lowercase()) {
        "content" -> return uri.takeIf { context.canOpenUri(it) }
        "file" -> return uri.path
            ?.let(::File)
            ?.takeIf { it.isFile }
            ?.let { Uri.fromFile(it) }
        "http", "https" -> return path
    }

    val directFile = File(path)
    if (directFile.isAbsolute) return directFile.takeIf { it.isFile }

    return listOf(
        File(context.filesDir, path),
        File(context.cacheDir, path),
    ).firstOrNull { it.isFile }
}

private fun Context.canOpenUri(uri: Uri): Boolean =
    runCatching {
        contentResolver.openInputStream(uri)?.use { true } == true
    }.getOrDefault(false)

/** Neutral background for an inconclusive result: neither the referral nor the all-clear colour. */
private val InconclusiveBackground = Color(0xFFF1F1EF)

private fun isNegative(outcome: Outcome) = outcome == Outcome.NEGATIVE
