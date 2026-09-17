package com.elevencapital.app

import android.view.ViewGroup.LayoutParams
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.NightMode
import com.elevencapital.app.ui.BrandLogo
import org.junit.Rule
import org.junit.Test

/**
 * Review sheet of the bundled production artwork rendered by Android Layoutlib.
 * Layoutlib lacks the device overlay that supplies the adaptive mask. The launcher preview
 * uses the actual production foreground within an explicit circular mask at 108:72 scaling.
 * This is visual QA for contrast, centering, and clipping, not a pixel-parity assertion against
 * the compressed source images. Record only this class with :app:recordPaparazziDebug.
 */
class BrandingScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig(
            screenWidth = 392,
            screenHeight = 826,
            xdpi = 160,
            ydpi = 160,
            density = Density.MEDIUM,
            nightMode = NightMode.NIGHT,
            fontScale = 1f,
            locale = "en-rUS",
            softButtons = false,
        ),
        theme = "Theme.ElevenCapital",
        showSystemUi = false,
        useDeviceResolution = true,
        maxPercentDifference = 0.0,
    )

    @Test
    fun productionBrandingContactSheet() {
        val view = ComposeView(paparazzi.context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setContent {
                Column(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF08090E)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Label("Eleven Capital", size = 24, weight = FontWeight.SemiBold)
                        Label("Production artwork · 392 × 826", Color(0xFF93949A), 12)
                    }
                    BrandSurface(onLightBackground = false)
                    BrandSurface(onLightBackground = true)
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Label("Android system surfaces", size = 15, weight = FontWeight.Medium)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top,
                        ) {
                            SystemSample("Launcher · circle") {
                                Box(Modifier.size(88.dp).clip(CircleShape).background(Color(0xFF171717)),
                                    contentAlignment = Alignment.Center) {
                                    Image(painterResource(R.drawable.eleven_launcher_foreground), null,
                                        modifier = Modifier.requiredSize(132.dp))
                                }
                            }
                            SystemSample("Monochrome layer") {
                                Box(
                                    Modifier.size(88.dp).clip(CircleShape).background(Color(0xFF332C45)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Image(
                                        painterResource(R.drawable.eleven_launcher_monochrome),
                                        contentDescription = null,
                                        modifier = Modifier.requiredSize(132.dp),
                                        colorFilter = ColorFilter.tint(Color(0xFFE9DDFF)),
                                    )
                                }
                            }
                            SystemSample("Splash icon") {
                                Box(Modifier.size(88.dp).background(Color(0xFF171717))) {
                                    PlatformDrawable(R.drawable.eleven_splash_mark, Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Label("Starting window", size = 15, weight = FontWeight.Medium)
                        AndroidView(
                            modifier = Modifier.fillMaxWidth().height(128.dp),
                            factory = { context ->
                                android.view.View(context).apply {
                                    setBackgroundResource(R.drawable.eleven_launch_background)
                                }
                            },
                        )
                    }
                }
            }
        }
        paparazzi.snapshot(view, name = "branding_production_392px")
    }

    @Composable
    private fun BrandSurface(onLightBackground: Boolean) {
        val foreground = if (onLightBackground) Color(0xFF171717) else Color(0xFFF4F4F4)
        val background = if (onLightBackground) Color(0xFFE4E4E4) else Color(0xFF171717)
        Column(
            Modifier.fillMaxWidth().background(background, RoundedCornerShape(18.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Label(if (onLightBackground) "Light surface" else "Dark surface", foreground, 13)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(30.dp),
            ) {
                BrandLogo(Modifier.size(104.dp), onLightBackground = onLightBackground)
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        BrandLogo(Modifier.size(24.dp), onLightBackground = onLightBackground)
                        Label("24dp", foreground, 12)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        BrandLogo(Modifier.size(44.dp), onLightBackground = onLightBackground)
                        Label("44dp", foreground, 12)
                    }
                }
            }
        }
    }

    @Composable
    private fun SystemSample(label: String, content: @Composable () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            content()
            Spacer(Modifier.height(10.dp))
            Label(label, Color(0xFFB5B5BC), 10)
        }
    }

    @Composable
    private fun PlatformDrawable(resource: Int, modifier: Modifier) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageResource(resource)
                }
            },
        )
    }

    @Composable
    private fun Label(
        text: String,
        color: Color = Color(0xFFF4F4F4),
        size: Int = 14,
        weight: FontWeight = FontWeight.Normal,
    ) {
        BasicText(text, style = TextStyle(color = color, fontSize = size.sp, fontWeight = weight,
            textAlign = TextAlign.Start))
    }
}
