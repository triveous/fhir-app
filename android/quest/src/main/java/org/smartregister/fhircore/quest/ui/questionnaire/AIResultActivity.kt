package org.smartregister.fhircore.quest.ui.questionnaire

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
            .background(if (isSuspicious) Color(0xFFFFF8E1) else Color(0xFFE8F5E9))
    ) {
        // Top App Bar
        TopAppBar(
            title = {
                Text(
                    text = "Add New Case",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
            },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_menu_close_clear_cancel),
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
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
        Spacer(modifier = Modifier.height(80.dp))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            // Warning Icon/Illustration
            Box(
                modifier = Modifier
                    .size(200.dp),
                contentAlignment = Alignment.Center
            ) {
                // Circular background
                Surface(
                    modifier = Modifier.size(180.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = Color(0xFFFFE5B4)
                ) {}

                // Warning card
                Surface(
                    modifier = Modifier
                        .size(120.dp)
                        .offset(x = 40.dp, y = (-20).dp),
                    shape = MaterialTheme.shapes.medium,
                    color = Color(0xFFFF9800),
                    shadowElevation = 4.dp
                ) {
                    Box(
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "!",
                            fontSize = 64.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            Text(
                text = "Doctor's review needed",
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF424242),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Check the recommendation\nsection for next steps.",
                fontSize = 16.sp,
                color = Color(0xFF757575),
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
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
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
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
        Spacer(modifier = Modifier.height(80.dp))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            // Success Icon/Illustration
            Box(
                modifier = Modifier
                    .size(200.dp),
                contentAlignment = Alignment.Center
            ) {
                // Circular background
                Surface(
                    modifier = Modifier.size(180.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = Color(0xFFC8E6C9)
                ) {}

                // Success card
                Surface(
                    modifier = Modifier
                        .size(120.dp)
                        .offset(x = 40.dp, y = (-20).dp),
                    shape = MaterialTheme.shapes.medium,
                    color = Color(0xFF4CAF50),
                    shadowElevation = 4.dp
                ) {
                    Box(
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✓",
                            fontSize = 64.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            Text(
                text = "No signs found",
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF424242),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "In case of doubts, reach out\nto your supervisor.",
                fontSize = 16.sp,
                color = Color(0xFF757575),
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
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
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
        }
    }
}