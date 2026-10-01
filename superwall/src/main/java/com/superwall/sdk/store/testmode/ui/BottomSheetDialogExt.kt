package com.superwall.sdk.store.testmode.ui

import android.graphics.Color
import android.view.View
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Sets [view] as the sheet's content. The sheet container Material wraps it in has its own
 * opaque background, which would show square corners behind the view's rounded background,
 * so the container is made transparent.
 */
internal fun BottomSheetDialog.setSheetContent(view: View) {
    setContentView(view)
    findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        ?.setBackgroundColor(Color.TRANSPARENT)
}
