package org.smartregister.fhircore.quest.ui.questionnaire

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import java.io.File
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
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.util.PostHogAnalytics
import org.smartregister.fhircore.quest.theme.Colors
import org.smartregister.fhircore.quest.theme.Colors.WHITE
import org.smartregister.fhircore.quest.theme.body18Medium
import org.smartregister.fhircore.quest.ui.register.customui.ZoomableImageView

class AIResultActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isSuspicious = intent.getBooleanExtra("isSuspicious", false)
        val suspiciousImages = intent.getStringArrayListExtra("suspiciousImages") ?: emptyList<String>()

        PostHogAnalytics.capture(
            PostHogAnalytics.Events.AI_RESULT_VIEWED,
            mapOf(PostHogAnalytics.Props.IS_SUSPICIOUS to isSuspicious),
        )

        setContent {
            MaterialTheme {
                AIResultScreen(
                    isSuspicious = isSuspicious,
                    suspiciousImages = suspiciousImages,
                    onClose = {
                        intent.putExtra("refer_case", false)
                        setResult(RESULT_OK, intent)
                        finish()
                    },
                    onRefer = {
                        PostHogAnalytics.capture(
                            PostHogAnalytics.Events.AI_REFER_CASE,
                            mapOf(PostHogAnalytics.Props.IS_SUSPICIOUS to isSuspicious),
                        )
                        intent.putExtra("refer_case", true)
                        setResult(RESULT_OK, intent)
                        finish()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIResultScreen(
    isSuspicious: Boolean,
    suspiciousImages: List<String>,
    onClose: () -> Unit,
    onRefer: () -> Unit
) {
    val backgroundColor = if (isSuspicious) Colors.CORNSILK else LighterBlue
    val title = if (isSuspicious) stringResource(R.string.add_patient) else "AI Result"
    var fullScreenImageUrl by remember { mutableStateOf<String?>(null) }

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
                    Image(
                        painter = painterResource(
                            id = if (isSuspicious) R.drawable.positive_sign else R.drawable.negative_sign
                        ),
                        contentDescription = "Result Illustration",
                        modifier = Modifier.size(120.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(
                        if (isSuspicious) R.string.suspicious_result else R.string.nonsuspicious_result
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
                    text = stringResource(
                        if (isSuspicious) R.string.suspicious_result_desc else R.string.nonsuspicious_result_desc
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
                    text = stringResource(R.string.ai_detected_lesions),
                    style = body18Medium().copy(
                        color = Color(0xAB2F363D),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.8.sp
                    ),
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                if (isSuspicious && suspiciousImages.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(suspiciousImages) { imagePath ->
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
                } else if (!isSuspicious) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.64f)),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = stringResource(R.string.none),
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
                            text = stringResource(R.string.close).uppercase(),
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
                                text = stringResource(R.string.refer_case),
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
    AndroidView(
        factory = { ctx ->
            androidx.appcompat.widget.AppCompatImageView(ctx).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            }
        },
        modifier = modifier,
        update = { view ->
            val finalPath = if (File(path).isAbsolute) path else File(context.filesDir, path).absolutePath
            Glide.with(view.context)
                .load(finalPath)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.ic_menu_gallery)
                .into(view)
        }
    )
}

@Composable
fun FullscreenImageOverlay(
    path: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val finalPath = if (File(path).isAbsolute) path else File(context.filesDir, path).absolutePath
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
            // Blurred background image
            AndroidView(
                factory = { ctx ->
                    androidx.appcompat.widget.AppCompatImageView(ctx).apply {
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .blur(10.dp) // Strong blur for background
                    .background(Color.Black.copy(alpha = 0.52f)), // Adjust opacity
                update = { view ->
                    Glide.with(view.context)
                        .load(finalPath)
                        .into(view)
                }
            )

            // Main Zoomable Image
            AndroidView(
                factory = { ctx ->
                    ZoomableImageView(ctx)
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    Glide.with(view.context)
                        .load(finalPath)
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
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
