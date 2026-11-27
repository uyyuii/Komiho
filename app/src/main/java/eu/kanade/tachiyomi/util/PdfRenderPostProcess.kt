package eu.kanade.tachiyomi.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.eink.EinkGray
import tachiyomi.core.common.util.system.ImageUtil
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Komiho (2026-11-04): 「已经是位图」的页面在显示前要做的处理。
 *
 * 与 [eu.kanade.tachiyomi.data.coil.TachiyomiImageDecoder] 解码之后那一段保持一致：
 * ① AI/Lanczos 增强 → ② 透明压白 → ③ E-Ink 灰阶。
 *
 * 存在的理由：PDF 渲染页由 PDFium 直接出位图，本来不必再「编码成 WebP/JPEG → 解码」；
 * 但增强逻辑原本只挂在解码器里，于是那条路径要么丢 AI、要么白付一次编码+解码。
 */
object PdfRenderPostProcess {

    /** 与解码器一致的条漫判定（高宽比）。 */
    private const val TALL_ASPECT_RATIO = 2.5f

    /**
     * 处理结果。
     *
     * [enhanceMillis] 是**真实**的计算耗时（-1 = 没有跑增强，例如增强关闭或被跳过门命中），
     * 调用方要把它传给 `PagerPreparedPage.enhanceElapsedMillis` —— 否则角标会显示成「跳过」，
     * 即使 AI 其实已经跑过（之前 PDF 页被误判成「没走 NPU」就是这个原因）。
     */
    data class Result(val bitmap: Bitmap, val enhanceMillis: Long)

    /**
     * 处理一张「已解码/已渲染」的位图，返回最终用于显示的位图（入参可能被回收）。
     *
     * 步骤：① 增强 → ② 透明压白 → ③ E-Ink 灰阶 → ④ PDF 专用裁边留白。
     * 第 ④ 步读取的偏好只有 PDF 渲染页会到达这里，天然不会对普通漫画页生效。
     *
     * @param viewWidth / viewHeight 当前视图尺寸，仅用于长条页判定与面积回缩。
     */
    fun process(
        bitmap: Bitmap,
        viewWidth: Int,
        viewHeight: Int,
        pageIndex: Int,
    ): Result {
        var bmp = bitmap
        if (bmp.isRecycled) return Result(bmp, -1L)

        val prefs = Injekt.get<ReaderPreferences>()
        val mode = prefs.enhancementMode.get()
        val denoise = prefs.denoiseLevel.get()

        // 必须在增强**之前**判断：增强按 RGB 处理会把 alpha 写成 255，透明区变黑就分辨不出来了。
        val srcTransparent = ImageUtil.hasTransparentPixels(bmp)

        var enhanceMillis = -1L

        // ① 增强：渲染页**不做「放大门限」跳过**。
        //
        // 解码器里那条 `fitRatio <= 1 就跳过` 的门是给原始扫描图用的（源图已比屏幕细就不必 2x）。
        // 但 PDF 渲染页的像素是 PDFium 按 2000 长边栅格化出来的，本身已经丢过细节，
        // 交给 AI 正是它的价值所在；老路径（字节 → Coil）也因为解码时按 2 的幂采样，
        // 解出的宽度略小于视图宽，r 常常 > 1，所以一直是有 AI 的。这里保持同等行为。
        if (mode != 0 || denoise != 0) {
            val tall = bmp.height > 0 && bmp.width > 0 &&
                bmp.height.toFloat() / bmp.width.toFloat() > TALL_ASPECT_RATIO
            val startedAt = SystemClock.uptimeMillis()
            val enhancedOrNull = runCatching {
                MihonSyEnhancer.enhance(
                    bmp,
                    prefs,
                    onComplete = { _, _, _ -> },
                    sourceTag = "pdf#$pageIndex",
                    pageIndex = pageIndex,
                )
            }.getOrNull()

            if (enhancedOrNull != null && enhancedOrNull !== bmp && !enhancedOrNull.isRecycled) {
                var result = enhancedOrNull
                // 与解码器一致：开了「AI 面积回缩」时把 2x 结果压回显示尺寸（防摩尔纹）。
                if (mode == 5 && prefs.aiAreaDownscale.get()) {
                    val downscaled = runCatching {
                        MihonSyEnhancer.areaDownscaleToDisplay(
                            result,
                            viewWidth = viewWidth,
                            viewHeight = viewHeight,
                            isTallStrip = tall,
                            strength = prefs.aiAreaDownscaleStrength.get() / 100f,
                        )
                    }.getOrNull()
                    if (downscaled != null && downscaled !== result) {
                        result.recycle()
                        result = downscaled
                    }
                }
                bmp.recycle()
                bmp = result
                enhanceMillis = SystemClock.uptimeMillis() - startedAt
                // 角标与「已增强」判定都读这里；put 后由 take() 消费。
                EnhanceTimings.put(pageIndex, enhanceMillis)
            }
        }

        // ② 透明压白（增强破坏 alpha 后统一合成到白底，与 PDF 渲染同口径）
        if (srcTransparent) {
            val flattened = ImageUtil.flattenOnWhite(bmp)
            if (flattened != null && flattened !== bmp) {
                bmp.recycle()
                bmp = flattened
            }
        }

        // ③ E-Ink 灰阶（必须在软件位图阶段；硬件位图禁止 setPixels）
        val eink = Injekt.get<UiPreferences>()
        if (eink.isEinkGrayscaleActive) {
            runCatching {
                EinkGray.apply(
                    bmp,
                    EinkGray.Config(
                        levels = eink.einkGrayLevelsOrDefault(),
                        keepColorPages = eink.einkKeepColorPages.get(),
                        curve = EinkGray.Curve.SOFT,
                        dither = false,
                    ),
                )
            }
        }

        // ④ PDF 裁边留白：按较短边取百分比，四周补白。
        // 只有 PDF 渲染页会走 PdfRenderPostProcess，所以普通图片/漫画章节不会受影响。
        bmp = addCropMargin(bmp)

        return Result(bmp, enhanceMillis)
    }

    /**
     * PDF 专用「裁边留白」——单独抽出，供**不走 process() 的字节路径**复用。
     *
     * 背景：位图旁路（单页 / 条漫非拆分）由 [process] 第 ④ 步加留白；但双页模式、
     * 超大页回落字节路径走 `PdfPageLoader.openStream()`，那里输出的是字节、不经过
     * [process]，漏掉留白 —— 表现就是「横屏/双页下开了留白却没白边」。
     * 字节路径在编码前把渲染位图过一遍本函数，两条路径口径就一致了。
     *
     * 注意：本函数**只补白**，不做 AI 增强 / E-Ink 灰阶 —— 字节路径后续还要经 Coil
     * 解码器做增强与裁边，若在这里重复处理会增强两次。
     *
     * @return 补白后的新位图（入参会被回收）；留白关闭 / 比例为 0 / 位图无效时原样返回入参。
     */
    fun addCropMargin(bitmap: Bitmap): Bitmap {
        val prefs = Injekt.get<ReaderPreferences>()
        if (!prefs.pdfCropMarginEnabled.get()) return bitmap
        val percent = prefs.pdfCropMarginPercent.get().coerceIn(0, 20)
        if (percent <= 0 || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return bitmap
        val margin = (minOf(bitmap.width, bitmap.height) * percent / 100f).toInt().coerceAtLeast(1)
        val padded = runCatching {
            Bitmap.createBitmap(
                bitmap.width + margin * 2,
                bitmap.height + margin * 2,
                Bitmap.Config.ARGB_8888,
            )
        }.getOrNull() ?: return bitmap
        val canvas = Canvas(padded)
        // SY --> Komiho 修复「留白看不见」：纯白边在白底主题（含 E-Ink 恒白）下与背景同色，
        // 视觉上完全消失。按当前阅读主题取对比色：白底用浅灰、深色底用纯白，边距始终可见。
        val marginColor = if (prefs.effectiveReaderTheme == eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.ReaderTheme.WHITE) {
            0xFFE8E8E8.toInt()
        } else {
            Color.WHITE
        }
        canvas.drawColor(marginColor)
        // SY <--
        canvas.drawBitmap(bitmap, margin.toFloat(), margin.toFloat(), null)
        bitmap.recycle()
        return padded
    }
}
