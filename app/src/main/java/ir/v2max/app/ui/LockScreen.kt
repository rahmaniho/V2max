package ir.v2max.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** صفحه‌ی قفل برنامه؛ تا زمان احراز هویت موفق نمایش داده می‌شود */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    AppBackground {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .then(Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = V2.Mint,
                        modifier = Modifier.size(48.dp)
                    )
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    "V2max قفل است",
                    style = MaterialTheme.typography.headlineSmall,
                    color = V2.TextPrimary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "برای مشاهده‌ی سرورها و تنظیمات، هویت خود را تأیید کنید.",
                    style = MaterialTheme.typography.bodySmall,
                    color = V2.TextMuted,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                PrimaryButton(
                    text = "باز کردن با اثر انگشت / رمز",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onUnlock
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "می‌توانید قفل برنامه را از تنظیمات خاموش کنید.",
                    fontSize = 10.sp,
                    color = V2.TextMuted
                )
            }
        }
    }
}
