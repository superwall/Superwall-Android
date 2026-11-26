package com.example.superapp.utils

import com.dropbox.differ.Color
import com.dropbox.differ.Image
import com.dropbox.differ.ImageComparator
import com.dropbox.differ.Mask
import com.dropbox.differ.SimpleImageComparator

class ModifiedImage(
    private val original: Image,
    private val systemBarHeight: Int,
) : Image {
    override val width: Int = original.width
    override val height: Int = original.height - systemBarHeight

    override fun getPixel(
        x: Int,
        y: Int,
    ): Color = original.getPixel(x, y + systemBarHeight)
}

/**
 * Screenshot comparator that leaves the status bar out, as its clock and icons change between
 * runs. The height is the device's own: a phone with a camera cutout has a much taller one
 * than the emulator.
 */
class CustomComparator(
    // How far a pixel's colour may be from the baseline's before it counts as different, on a
    // 0 to 1 scale. 0.004 is one step in 255, and a paywall's background photo comes out two or
    // three steps off between runs on the same device, enough to fail a 1% limit with nothing
    // visibly different. 0.02 is five steps.
    maxDistance: Float = 0.02f,
    hShift: Int = 0,
    vShift: Int = 0,
    private val systemBarHeight: Int = statusBarHeightPx(),
) : ImageComparator {
    private val simpleComparator = SimpleImageComparator(maxDistance, hShift, vShift)

    override fun compare(
        left: Image,
        right: Image,
        mask: Mask?,
    ): ImageComparator.ComparisonResult {
        val modifiedLeft = ModifiedImage(left, systemBarHeight)
        val modifiedRight = ModifiedImage(right, systemBarHeight)
        val res = simpleComparator.compare(modifiedLeft, modifiedRight, mask)

        // This mask is the one containing diffed pixels, so we offset it by systemBarHeight
        mask?.let {
            for (x in 0 until mask.width) {
                for (y in mask.height - systemBarHeight - 1 downTo 0) {
                    mask.setValue(x, y + systemBarHeight, mask.getValue(x, y))
                }
                for (y in 0 until systemBarHeight) {
                    mask.setValue(x, y, 0f)
                }
            }
        }
        return res
    }
}
