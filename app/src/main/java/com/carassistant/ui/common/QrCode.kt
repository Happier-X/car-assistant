package com.carassistant.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 推送地址二维码。
 *
 * ## 为什么用 zxing（本项目唯一的第三方依赖）
 *
 * 手写 QR 编码器一旦有错，表现是「手机能打开页面、但相机扫不出来」——
 * 这种故障只能在真机上发现，用户在车机上完全无法自助排查。
 * 相比之下 zxing core 是纯 Java、无传递依赖、约 600KB，代价远小于
 * 「二维码是坏的却没人知道」。所以这是「零第三方依赖」的唯一刻意例外，
 * 理由也写进了 README 的设计取舍。
 *
 * ## 几个必须遵守的细节
 *
 * 1. **黑模块画在黑底上会扫不出来**：二维码永远是白底黑块，不能跟随深色主题反色，
 *    也不能加半透明遮罩（会降低对比度）。
 * 2. **静默区**：四周留 2 个模块的白边（EncodeHintType.MARGIN），少了识别率会明显下降。
 * 3. **不要压缩到模糊**：边长按车机短边的 45% 计算，并限制在 240~280dp，
 *    位图按实际像素生成（不是先画小再放大）。
 * 4. **生成失败不能崩页面**：解析失败时降级成「请手输地址」的白底提示。
 * 5. **组合阶段不做重活**：按内容 + 像素尺寸 remember 缓存，地址变了才重新编码。
 */
@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
    minSize: androidx.compose.ui.unit.Dp = 240.dp,
    maxSize: androidx.compose.ui.unit.Dp = 280.dp,
) {
    if (content.isBlank()) return

    val configuration = LocalConfiguration.current
    val shortSide = minOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
    // 车机屏幕短边的 45%，但不小于 240dp、不大于 280dp
    val size = maxOf(minSize, shortSide * 0.45f).coerceAtMost(maxSize)

    val density = LocalDensity.current
    val pixels = with(density) { size.roundToPx() }.coerceAtLeast(64)

    // 地址变化（换热点/换 IP/换端口）时会重新生成；同一地址不会重复编码
    val bitmap = remember(content, pixels) {
        runCatching { encodeQr(content, pixels) }.getOrNull()
    }

    if (bitmap == null) {
        // 降级：白底黑字的提示，绝不让页面空白或崩溃
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "二维码生成失败\n请手输上面的推送地址",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Black,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "手机推送地址二维码",
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White),
            contentScale = ContentScale.Fit,
        )
    }
}

/**
 * 把文本编码成二维码位图。
 *
 * 纠错等级用 M（15% 容错，手机摄像头识别率与信息量的平衡点），
 * 字符集 UTF-8，静默区 2 个模块。zxing 会自动选择最小的可用版本（1~40）。
 */
private fun encodeQr(content: String, sizePx: Int): Bitmap {
    val hints = mapOf<EncodeHintType, Any>(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 2,
    )
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        hints,
    )

    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val rowOffset = y * width
        for (x in 0 until width) {
            pixels[rowOffset + x] = if (matrix.get(x, y)) BLACK else WHITE
        }
    }
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, width, 0, 0, width, height)
    }
}

private const val BLACK = -0x1000000 // 0xFF000000
private const val WHITE = -0x1        // 0xFFFFFFFF
