package org.smartregister.fhircore.quest.ui.questionnaire

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.smartregister.fhircore.engine.ui.theme.PrimaryColor
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.theme.Colors.WHITE
import org.smartregister.fhircore.quest.theme.body18Medium

class AIResultActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isSuspicious = intent.getBooleanExtra("isSuspicious", false)

        setContent {
            MaterialTheme {
                AIResultScreen(
                    isSuspicious = isSuspicious,
                    onClose = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIResultScreen(
    isSuspicious: Boolean,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isSuspicious) Color(0xFFFFF8E0) else Color(0xFFE9FFF3))
    ) {
        // Top App Bar
        TopAppBar(
            title = {
                Text(
                    text = "Add New Case",
                    fontSize = 20.sp,
                    style = body18Medium().copy(color = WHITE)

                )
            },
            navigationIcon = {
//                IconButton(onClick = onClose) {
//                    Icon(
//                        painter = painterResource(id = android.R.drawable.ic_menu_close_clear_cancel),
//                        contentDescription = "Back",
//                        tint = Color.White
//                    )
//                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color(0xFF1976D2)
            )
        )

        // Content
        if (isSuspicious) {
            SuspiciousResultContent(onClose = onClose)
        } else {
            NonSuspiciousResultContent(onClose = onClose)
        }
    }
}

@Composable
fun SuspiciousResultContent(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            // Warning Icon/Illustration
            Box(
                modifier = Modifier
                    .size(124.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.positive_sign),
                        contentDescription = "Warning sign",
                        modifier = Modifier.size(120.dp)
                    )
                }
            }


            Text(
                text = stringResource(R.string.suspicious_result),
                style = body18Medium().copy(color = Color(0xFF424242), fontSize = 20.sp)
            )

            Text(
                text = stringResource(R.string.suspicious_result_desc),
                style = body18Medium().copy(color = Color(0xFF767B72), fontWeight = FontWeight.W400,  lineHeight = 1.5.em),
                textAlign = TextAlign.Center
            )
        }

        // Close Button
        Button(
            onClick = onClose,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = PrimaryColor
            ),
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = "CLOSE",
                style = body18Medium().copy(color = WHITE)
            )
        }
    }
}

@Composable
fun NonSuspiciousResultContent(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            // Success Icon/Illustration
            Box(
                modifier = Modifier
                    .size(124.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.negative_sign),
                        contentDescription = "No signs found icon",
                        modifier = Modifier.size(120.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.nonsuspicious_result),
                style = body18Medium().copy(color = Color(0xFF424242), fontSize = 20.sp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.nonsuspicious_result_desc),
                style = body18Medium().copy(color = Color(0xFF767B72), fontWeight = FontWeight.W400, lineHeight = 1.5.em),
                textAlign = TextAlign.Center
            )
        }

        // Close Button
        Button(
            onClick = onClose,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1976D2)
            ),
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = "CLOSE",
                style = body18Medium().copy(color = WHITE)
            )
        }
    }
}