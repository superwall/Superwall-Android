package com.superwall.sdk.customercenter

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.superwall.sdk.R
import com.superwall.sdk.Superwall
import kotlinx.coroutines.launch
import java.text.DateFormat

/**
 * The Customer Center's screen. Presented by [com.superwall.sdk.Superwall.presentCustomerCenter];
 * not meant to be started directly.
 */
class CustomerCenterActivity : AppCompatActivity() {
    private var session: CustomerCenterManager.Session? = null
    private val viewModel: CustomerCenterViewModel get() = session!!.viewModel
    private val strings: CustomerCenterStrings get() = viewModel.strings

    private lateinit var toolbar: MaterialToolbar
    private lateinit var content: FrameLayout
    private lateinit var scrollView: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var loadingCover: View
    private lateinit var restoreOverlay: View

    /** The purchase whose detail screen is showing, if one is. */
    private var detailPurchaseId: String? = null
    private var shownSheet: CustomerCenterSheet? = null
    private var sheetDialog: android.app.Dialog? = null
    private var restoreDialog: AlertDialog? = null
    private var copiedUserId = false
    private var lastState: CustomerCenterUiState? = null

    /** What rows, buttons and spinners are tinted with. See [CustomerCenterTint]. */
    private var tint: Int = CustomerCenterTint.FALLBACK_LIGHT

    private val backCallback =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                closeDetail()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val current = sessionHost()?.session
        if (current == null) {
            // Restored after the process died, or started directly: there's no presentation to
            // show, and no delegate waiting on one.
            finish()
            return
        }
        session = current
        current.activity.set(this)
        detailPurchaseId = savedInstanceState?.getString(STATE_DETAIL_PURCHASE_ID)
        tint =
            CustomerCenterTint.resolve(
                configured = configuredAccent(),
                host = CustomerCenterTint.hostColor(this, current.hostThemeResId),
                isDark = isDark(),
            )

        // Drawn edge to edge on every API level, so the insets below are always the ones to honour.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(buildLayout())
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark()
            isAppearanceLightNavigationBars = !isDark()
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
        viewModel.start()
    }

    override fun onResume() {
        super.onResume()
        session?.let {
            it.activity.set(this)
            it.viewModel.onResume()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_DETAIL_PURCHASE_ID, detailPurchaseId)
    }

    override fun onDestroy() {
        sheetDialog?.setOnDismissListener(null)
        sheetDialog?.dismiss()
        restoreDialog?.setOnDismissListener(null)
        restoreDialog?.dismiss()
        val current = session
        super.onDestroy()
        if (current != null && isFinishing) {
            sessionHost()?.sessionEnded(current)
        }
    }

    // region Layout

    private fun buildLayout(): View {
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(ContextCompat.getColor(context, R.color.superwall_customer_center_background))
            }
        toolbar =
            MaterialToolbar(this).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.superwall_customer_center_background))
            }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        list =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(24))
            }
        scrollView =
            ScrollView(this).apply {
                isFillViewport = true
                clipToPadding = false
                addView(list)
            }
        content.addView(scrollView)

        loadingCover =
            FrameLayout(this).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.superwall_customer_center_background))
                isClickable = true
                addView(
                    tintedProgressBar(),
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
                )
            }
        content.addView(loadingCover)

        restoreOverlay = buildRestoreOverlay()
        content.addView(restoreOverlay)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            toolbar.updatePadding(top = bars.top)
            view.updatePadding(left = bars.left, right = bars.right)
            list.updatePadding(bottom = dp(24) + bars.bottom)
            insets
        }
        return root
    }

    private fun buildRestoreOverlay(): View {
        val card =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(24), dp(24), dp(24), dp(24))
                background = roundedBackground(ContextCompat.getColor(context, R.color.superwall_customer_center_card), dp(16).toFloat())
                addView(tintedProgressBar())
                addView(
                    TextView(context).apply {
                        text = strings.string("customer_center_restoring")
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setPadding(0, dp(12), 0, 0)
                    },
                )
            }
        return FrameLayout(this).apply {
            setBackgroundColor(Color.argb(64, 0, 0, 0))
            isClickable = true
            visibility = View.GONE
            addView(
                card,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
        }
    }

    // endregion

    // region Rendering

    private fun render(state: CustomerCenterUiState) {
        lastState = state
        loadingCover.visibility = if (state.screen == CustomerCenterScreenState.LOADING) View.VISIBLE else View.GONE
        restoreOverlay.visibility = if (state.restoreState == CustomerCenterRestoreState.RESTORING) View.VISIBLE else View.GONE

        val detail = detailPurchaseId?.let { id -> state.purchases.firstOrNull { it.id == id } }
        if (detailPurchaseId != null && detail == null && state.screen != CustomerCenterScreenState.LOADING) {
            // The purchase has gone, say after a refresh. Go back to the list rather than showing
            // a detail screen for nothing.
            detailPurchaseId = null
        }
        backCallback.isEnabled = detail != null
        renderToolbar(state, detail)

        list.removeAllViews()
        when {
            state.screen == CustomerCenterScreenState.LOADING -> Unit
            detail != null -> renderDetail(state, detail)
            state.screen == CustomerCenterScreenState.MANAGEMENT -> renderManagement(state)
            else -> renderNoPurchases(state)
        }

        renderSheet(state.sheet)
        renderRestoreResult(state.restoreState)
    }

    private fun renderToolbar(
        state: CustomerCenterUiState,
        detail: PurchasePresentation?,
    ) {
        toolbar.title =
            when {
                detail != null -> detail.title.orEmpty()
                state.screen == CustomerCenterScreenState.MANAGEMENT ->
                    viewModel.configuration.managementScreen.title ?: strings.string("customer_center_management_title")
                else -> ""
            }
        if (detail != null) {
            toolbar.setNavigationIcon(R.drawable.superwall_customer_center_back)
            toolbar.navigationContentDescription = null
            toolbar.setNavigationOnClickListener { closeDetail() }
        } else {
            toolbar.setNavigationIcon(R.drawable.superwall_customer_center_close)
            toolbar.navigationContentDescription = strings.string("customer_center_close")
            toolbar.setNavigationOnClickListener { finish() }
        }
    }

    private fun renderManagement(state: CustomerCenterUiState) {
        if (state.showsUpdateBanner) list.addView(updateBanner())
        if (state.showsDuplicateBanner) list.addView(duplicateBanner())

        val subscriptions = state.purchases.filter { it.opensDetail }
        val others = state.purchases.filterNot { it.opensDetail }
        if (subscriptions.isNotEmpty()) {
            // Every subscription — and every entitlement-only purchase — is a row that opens its
            // own detail screen. This screen keeps the actions that apply to the account; anything
            // that only makes sense against one purchase lives where the row leads.
            addSection(
                strings.string("customer_center_section_subscriptions"),
                subscriptions.map { purchase ->
                    purchaseCard(purchase, state.refundResult, showsChevron = true).apply {
                        setOnClickListener { openDetail(purchase) }
                        background = selectableBackground()
                    }
                },
            )
        }
        if (others.isNotEmpty()) {
            addSection(strings.string("customer_center_section_purchases"), others.map { purchaseCard(it, null) })
        }
        addSection(strings.string("customer_center_section_actions"), pathRows(state, purchase = null, isScreenLevel = true))
        if (viewModel.configuration.showsAccountDetails) addAccountDetails()
    }

    private fun renderNoPurchases(state: CustomerCenterUiState) {
        val screen = viewModel.configuration.noPurchasesScreen
        val header =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(text(screen.title ?: strings.string("customer_center_no_purchases_title"), 17f, bold = true))
                addView(
                    text(screen.subtitle ?: strings.string("customer_center_no_purchases_subtitle"), 15f, secondary = true).apply {
                        setPadding(0, dp(4), 0, 0)
                    },
                )
            }
        addSection(null, listOf(header))
        addSection(null, pathRows(state, purchase = null, isScreenLevel = true))
        if (viewModel.configuration.showsAccountDetails) addAccountDetails()
    }

    private fun renderDetail(
        state: CustomerCenterUiState,
        purchase: PurchasePresentation,
    ) {
        addSection(null, listOf(purchaseCard(purchase, state.refundResult)))
        val empty = viewModel.detailEmptyState(purchase)
        if (empty != null) {
            // The row opened this screen regardless, so say what there is to say rather than head
            // an empty list with "Actions".
            val sentence =
                when (empty) {
                    DetailEmptyState.NothingToDo -> strings.string("customer_center_detail_nothing_to_manage")
                    is DetailEmptyState.ManagedElsewhere ->
                        empty.storeLabelKey?.let {
                            strings.string("customer_center_detail_managed_through", strings.string(it))
                        } ?: strings.string("customer_center_detail_managed_where_bought")
                }
            addSection(null, listOf(text(sentence, 15f, secondary = true).apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }))
        } else {
            addSection(strings.string("customer_center_section_actions"), pathRows(state, purchase, isScreenLevel = false))
        }
    }

    private fun pathRows(
        state: CustomerCenterUiState,
        purchase: PurchasePresentation?,
        isScreenLevel: Boolean,
    ): List<View> =
        viewModel.paths(purchase, isScreenLevel).map { resolved ->
            val row =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(52)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    contentDescription = CustomerCenterPathTitles.title(resolved, strings)
                    tag = "customer_center.path.${resolved.id}"
                }
            val title =
                text(CustomerCenterPathTitles.title(resolved, strings), 16f).apply {
                    setTextColor(tint)
                }
            row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (state.busyPathId == resolved.id) {
                row.addView(tintedProgressBar(), LinearLayout.LayoutParams(dp(20), dp(20)))
            }
            val enabled = state.busyPathId == null
            row.isEnabled = enabled
            row.alpha = if (enabled || state.busyPathId == resolved.id) 1f else 0.5f
            row.background = selectableBackground()
            row.setOnClickListener { viewModel.onPathTapped(resolved, purchase) }
            row
        }

    private fun purchaseCard(
        purchase: PurchasePresentation,
        refundResult: Pair<String, CustomerCenterRefundStatus>?,
        showsChevron: Boolean = false,
    ): View {
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
        if (purchase.isAwaitingCatalogue) {
            // Placeholders for what the catalogue supplies: the name, the price, and a status line
            // that quotes the price.
            column.addView(placeholder(dp(160), dp(18)))
            column.addView(placeholder(dp(110), dp(14)))
            if (purchase.badge == PurchaseBadge.ACTIVE) {
                column.addView(placeholder(dp(220), dp(14)))
            } else {
                column.addView(text(purchase.statusLine, 14f, secondary = true))
            }
        } else {
            purchase.title?.let { column.addView(text(it, 17f, bold = true)) }
            purchase.priceLine?.let { column.addView(text(it, 14f)) }
            if (purchase.statusLine.isNotEmpty()) column.addView(text(purchase.statusLine, 14f, secondary = true))
        }
        purchase.storeLabelKey?.let { column.addView(text(strings.string(it), 12f, secondary = true)) }
        if (refundResult != null && refundResult.first == purchase.productId && refundResult.second == CustomerCenterRefundStatus.ERROR) {
            column.addView(
                text(strings.string("customer_center_refund_error"), 12f).apply {
                    setTextColor(ContextCompat.getColor(context, R.color.superwall_customer_center_badge_red))
                },
            )
        }
        for (i in 0 until column.childCount) {
            (column.getChildAt(i).layoutParams as? LinearLayout.LayoutParams)?.topMargin = if (i == 0) 0 else dp(4)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(14))
            tag = "customer_center.purchase.${purchase.productId ?: purchase.id}"
            addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                badge(purchase.badge),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(8)
                },
            )
            if (showsChevron) {
                addView(
                    text("›", 22f, secondary = true).apply { setPadding(dp(8), 0, 0, 0) },
                )
            }
        }
    }

    private fun badge(badge: PurchaseBadge): View {
        val (key, colorRes) =
            when (badge) {
                PurchaseBadge.ACTIVE -> "customer_center_badge_active" to R.color.superwall_customer_center_badge_green
                PurchaseBadge.LIFETIME -> "customer_center_badge_lifetime" to R.color.superwall_customer_center_badge_green
                PurchaseBadge.FREE_TRIAL -> "customer_center_badge_free_trial" to R.color.superwall_customer_center_badge_orange
                PurchaseBadge.CANCELLED -> "customer_center_badge_cancelled" to R.color.superwall_customer_center_badge_red
                PurchaseBadge.BILLING_ISSUE -> "customer_center_badge_billing_issue" to R.color.superwall_customer_center_badge_red
                PurchaseBadge.REVOKED -> "customer_center_badge_revoked" to R.color.superwall_customer_center_badge_red
                PurchaseBadge.EXPIRED -> "customer_center_badge_expired" to R.color.superwall_customer_center_badge_gray
            }
        val color = ContextCompat.getColor(this, colorRes)
        return TextView(this).apply {
            text = strings.string(key)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = roundedBackground(Color.argb(38, Color.red(color), Color.green(color), Color.blue(color)), dp(12).toFloat())
        }
    }

    private fun updateBanner(): View {
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(text(strings.string("customer_center_update_title"), 17f, bold = true))
                addView(text(strings.string("customer_center_update_message"), 14f, secondary = true).apply { setPadding(0, dp(4), 0, dp(8)) })
            }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(
            MaterialButton(this).apply {
                text = strings.string("customer_center_update_action")
                backgroundTintList = ColorStateList.valueOf(tint)
                setOnClickListener { viewModel.openAppListing() }
            },
        )
        buttons.addView(
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = strings.string("customer_center_update_continue")
                setTextColor(tint)
                setOnClickListener { viewModel.continueAfterUpdateWarning() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            },
        )
        column.addView(buttons)
        return card(listOf(column))
    }

    private fun duplicateBanner(): View {
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                tag = "customer_center.duplicate_warning"
                addView(text("⚠ " + strings.string("customer_center_duplicate_title"), 17f, bold = true))
                addView(text(strings.string("customer_center_duplicate_message"), 14f, secondary = true).apply { setPadding(0, dp(4), 0, 0) })
            }
        return card(listOf(column))
    }

    private fun addAccountDetails() {
        val userId = viewModel.userId
        val userRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(8), dp(10))
            }
        val labels =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(strings.string("customer_center_user_id"), 12f, secondary = true))
                addView(
                    text(userId, 13f).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                    },
                )
            }
        userRow.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        userRow.addView(
            MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = strings.string(if (copiedUserId) "customer_center_copied" else "customer_center_copy")
                setTextColor(tint)
                setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(strings.string("customer_center_user_id"), userId))
                    copiedUserId = true
                    text = strings.string("customer_center_copied")
                }
            },
        )
        val rows = mutableListOf<View>(userRow)
        viewModel.originalDownloadDate?.let { date ->
            rows.add(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    addView(
                        text(strings.string("customer_center_original_download_date"), 13f),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(text(DateFormat.getDateInstance(DateFormat.MEDIUM, viewModel.locale).format(date), 13f, secondary = true))
                },
            )
        }
        addSection(strings.string("customer_center_account_details"), rows)
    }

    // endregion

    // region Sheets and alerts

    private fun renderSheet(sheet: CustomerCenterSheet?) {
        if (sheet == shownSheet) return
        sheetDialog?.setOnDismissListener(null)
        sheetDialog?.dismiss()
        sheetDialog = null
        shownSheet = sheet
        sheetDialog =
            when (sheet) {
                null -> null
                is CustomerCenterSheet.Survey -> surveyDialog()
                is CustomerCenterSheet.NoMailApp -> messageDialog(strings.string("customer_center_no_mail_app", sheet.email))
                CustomerCenterSheet.WebManageUnavailable -> messageDialog(strings.string("customer_center_web_manage_unavailable"))
            }
        sheetDialog?.setOnDismissListener {
            shownSheet = null
            sheetDialog = null
            viewModel.sheetDismissed()
        }
        sheetDialog?.show()
        (sheetDialog as? AlertDialog)?.tintButtons()
    }

    private fun surveyDialog(): android.app.Dialog? {
        val (path, survey) = viewModel.pendingSurvey ?: return null
        val dialog = BottomSheetDialog(this)
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(24))
            }
        val header =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(8), dp(8), dp(8))
            }
        header.addView(
            text(CustomerCenterPathTitles.surveyTitle(survey, path, strings), 18f, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(
            MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = strings.string("customer_center_cancel")
                setTextColor(tint)
                setOnClickListener { dialog.dismiss() }
            },
        )
        column.addView(header)
        var answered = false
        for (option in survey.options) {
            column.addView(
                text(CustomerCenterPathTitles.optionTitle(option, strings), 16f).apply {
                    setPadding(dp(20), dp(14), dp(20), dp(14))
                    background = selectableBackground()
                    tag = "customer_center.survey.option.${option.id}"
                    setOnClickListener {
                        if (answered) return@setOnClickListener
                        answered = true
                        dialog.setOnDismissListener(null)
                        dialog.dismiss()
                        shownSheet = null
                        sheetDialog = null
                        viewModel.onSurveyAnswered(option.id)
                    }
                },
            )
        }
        dialog.setContentView(column)
        return dialog
    }

    private fun messageDialog(message: String): android.app.Dialog =
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton(strings.string("customer_center_done"), null)
            .create()

    private fun renderRestoreResult(restoreState: CustomerCenterRestoreState) {
        val isResult = restoreState == CustomerCenterRestoreState.RESTORED || restoreState == CustomerCenterRestoreState.NOT_FOUND
        if (!isResult) {
            restoreDialog?.setOnDismissListener(null)
            restoreDialog?.dismiss()
            restoreDialog = null
            return
        }
        if (restoreDialog != null) return
        val restored = restoreState == CustomerCenterRestoreState.RESTORED
        val builder =
            MaterialAlertDialogBuilder(this)
                .setTitle(strings.string(if (restored) "customer_center_restore_success_title" else "customer_center_restore_none_title"))
                .setMessage(strings.string(if (restored) "customer_center_restore_success_message" else "customer_center_restore_none_message"))
                .setPositiveButton(strings.string("customer_center_done"), null)
        if (!restored) {
            if (lastState?.showsUpdateBanner == true) {
                builder.setNeutralButton(strings.string("customer_center_update_action")) { _, _ -> viewModel.openAppListing() }
            }
            if (viewModel.supportMailtoUrl != null) {
                builder.setNegativeButton(strings.string("customer_center_path_contact_support")) { _, _ -> viewModel.contactSupport() }
            }
        }
        restoreDialog =
            builder.create().apply {
                setOnDismissListener {
                    restoreDialog = null
                    viewModel.restoreAlertDismissed()
                }
                show()
                tintButtons()
            }
    }

    // endregion

    // region Navigation

    private fun openDetail(purchase: PurchasePresentation) {
        detailPurchaseId = purchase.id
        scrollView.scrollTo(0, 0)
        lastState?.let(::render)
    }

    private fun closeDetail() {
        detailPurchaseId = null
        lastState?.let(::render)
    }

    // endregion

    // region View helpers

    private fun addSection(
        title: String?,
        rows: List<View>,
    ) {
        if (rows.isEmpty()) return
        if (title != null) {
            list.addView(
                text(title.uppercase(viewModel.locale), 12f, secondary = true).apply {
                    setPadding(dp(16), dp(16), dp(16), dp(6))
                },
            )
        }
        list.addView(card(rows, topMargin = if (title == null) dp(12) else 0))
    }

    private fun card(
        rows: List<View>,
        topMargin: Int = dp(12),
    ): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                column.addView(
                    View(this).apply { setBackgroundColor(themeColor(com.google.android.material.R.attr.colorOutlineVariant)) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(16) },
                )
            }
            column.addView(row)
        }
        return MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.superwall_customer_center_card))
            addView(column)
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    this.topMargin = topMargin
                }
        }
    }

    private fun text(
        value: String,
        sizeSp: Float,
        bold: Boolean = false,
        secondary: Boolean = false,
    ): TextView =
        TextView(this).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setTextColor(themeColor(if (secondary) android.R.attr.textColorSecondary else android.R.attr.textColorPrimary))
        }

    private fun placeholder(
        width: Int,
        height: Int,
    ): View =
        View(this).apply {
            background = roundedBackground(themeColor(com.google.android.material.R.attr.colorOutlineVariant), dp(4).toFloat())
            layoutParams = LinearLayout.LayoutParams(width, height)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val pulse =
                ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0.4f).apply {
                    duration = 800
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                }
            // Every render replaces the rows, so the pulse must stop with the view it animates.
            addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) = pulse.start()

                    override fun onViewDetachedFromWindow(view: View) = pulse.cancel()
                },
            )
        }

    private fun roundedBackground(
        color: Int,
        radius: Float,
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun selectableBackground() =
        TypedValue().let {
            theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
            ContextCompat.getDrawable(this, it.resourceId)
        }

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }

    /** The configured accent for the current light/dark mode, or `null` when none is configured. */
    private fun configuredAccent(): Int? {
        val pair = viewModel.configuration.appearance.accent ?: return null
        return CustomerCenterColors.parseHex(if (isDark()) pair.dark else pair.light)
    }

    private fun isDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun tintedProgressBar(): ProgressBar =
        ProgressBar(this).apply { indeterminateTintList = ColorStateList.valueOf(tint) }

    /** Dialog buttons take the theme's colour otherwise, which isn't the app's. Call after `show()`. */
    private fun AlertDialog.tintButtons() {
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
            .forEach { getButton(it)?.setTextColor(tint) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // endregion

    internal companion object {
        private const val STATE_DETAIL_PURCHASE_ID = "superwall_customer_center_detail_purchase_id"

        /** Where the activity finds its presentation. Replaceable so tests can host one. */
        internal var sessionHost: () -> CustomerCenterSessionHost? = {
            if (Superwall.initialized) Superwall.instance.dependencyContainer.customerCenterManager else null
        }
    }
}

/** What rows and surveys say. Separate from the activity so the rules can be tested directly. */
internal object CustomerCenterPathTitles {
    fun title(
        resolved: ResolvedPath,
        strings: CustomerCenterStrings,
    ): String {
        val path = resolved.path
        path.title?.let { return it }
        return when (val type = path.type) {
            CustomerCenterConfiguration.PathType.Restore -> strings.string("customer_center_path_restore")
            // "Cancel subscription" is right for Google Play, where the row carries the
            // cancellation survey and opens Play's subscription page. A web management page does
            // more than cancel, so naming it that way there undersells it.
            CustomerCenterConfiguration.PathType.ManageSubscription ->
                if (resolved.destination.isWebManagement) {
                    strings.string("customer_center_path_manage_subscription_web")
                } else {
                    strings.string("customer_center_path_manage_subscription")
                }
            is CustomerCenterConfiguration.PathType.Refund -> strings.string("customer_center_path_refund")
            is CustomerCenterConfiguration.PathType.ChangePlan -> strings.string("customer_center_path_change_plan")
            CustomerCenterConfiguration.PathType.ContactSupport -> strings.string("customer_center_path_contact_support")
            is CustomerCenterConfiguration.PathType.Url -> CustomerCenterUrls.host(type.url) ?: type.url
            is CustomerCenterConfiguration.PathType.Custom -> type.identifier
        }
    }

    /**
     * The survey's own title, else the cancellation question on the path that cancels. Any other
     * path gets no title rather than asking the customer why they're cancelling.
     */
    fun surveyTitle(
        survey: CustomerCenterConfiguration.FeedbackSurvey,
        path: CustomerCenterConfiguration.Path,
        strings: CustomerCenterStrings,
    ): String =
        survey.title
            ?: if (path.type == CustomerCenterConfiguration.PathType.ManageSubscription) {
                strings.string("customer_center_survey_cancel_title")
            } else {
                ""
            }

    fun optionTitle(
        option: CustomerCenterConfiguration.FeedbackSurvey.Option,
        strings: CustomerCenterStrings,
    ): String =
        option.title ?: when (option.id) {
            "too_expensive" -> strings.string("customer_center_survey_too_expensive")
            "dont_use" -> strings.string("customer_center_survey_dont_use")
            "bought_by_mistake" -> strings.string("customer_center_survey_bought_by_mistake")
            else -> option.id
        }
}
