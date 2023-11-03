package com.superwall.sdk.debug

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.superwall.sdk.BuildConfig
import com.superwall.sdk.R
import com.superwall.sdk.Superwall
import com.superwall.sdk.delegate.SubscriptionStatus
import com.superwall.sdk.dependencies.RequestFactory
import com.superwall.sdk.dependencies.ViewControllerFactory
import com.superwall.sdk.models.paywall.Paywall
import com.superwall.sdk.network.Network
import com.superwall.sdk.paywall.manager.PaywallManager
import com.superwall.sdk.paywall.presentation.internal.PresentationRequestType
import com.superwall.sdk.paywall.presentation.internal.internallyPresent
import com.superwall.sdk.paywall.presentation.internal.request.PresentationInfo
import com.superwall.sdk.paywall.presentation.internal.state.PaywallSkippedReason
import com.superwall.sdk.paywall.presentation.internal.state.PaywallState
import com.superwall.sdk.paywall.request.PaywallRequestManager
import com.superwall.sdk.paywall.request.ResponseIdentifiers
import com.superwall.sdk.paywall.vc.ActivityEncapsulatable
import com.superwall.sdk.paywall.vc.ViewStorage
import com.superwall.sdk.store.StoreKitManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import Logger

internal class DebugViewController(
    context: Context,
    private val storeKitManager: StoreKitManager,
    private val network: Network,
    private val paywallRequestManager: PaywallRequestManager,
    private val paywallManager: PaywallManager,
   // private val debugManager: DebugManager,
    private val factory: Factory
) : ConstraintLayout(context), ActivityEncapsulatable {
    interface Factory: RequestFactory, ViewControllerFactory {}
    data class AlertOption(
        val title: String? = "",
        val action: (suspend () -> Unit)? = null,
        val style: Int = AlertDialog.BUTTON_POSITIVE
    )
    // The full screen activity instance if this view controller has been presented in one.
    override var encapsulatingActivity: Activity? = null

    private var paywallDatabaseId: String? = null
    private var paywallIdentifier: String? = null
    private var initialLocaleIdentifier: String? = null
    private var previewViewContent: View? = null
    private var paywalls: List<Paywall> = emptyList()
    private var paywall: Paywall? = null

    private val logoImageView: ImageView by lazy {
        ImageView(context).apply {
            val superwallLogo = ContextCompat.getDrawable(context, R.drawable.superwall_logo)
            setImageDrawable(superwallLogo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.TRANSPARENT)
            clipToOutline = true
            visibility = View.VISIBLE
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
    }
    private val exitButton: Button by lazy {
        Button(context).apply {
            val exitImage = ContextCompat.getDrawable(context, R.drawable.exit)
            background = exitImage
            alpha = 0.5f // equivalent to UIColor.white.withAlphaComponent(0.5)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            setOnClickListener { pressedExitButton() }
        }
    }
    private val consoleButton: Button by lazy {
        Button(context).apply {
            val debuggerImage = ContextCompat.getDrawable(context, R.drawable.debugger)
            background = debuggerImage
            alpha = 0.5f
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            setOnClickListener { pressedConsoleButton() }
        }
    }
    private val activityIndicator: ProgressBar by lazy {
        ProgressBar(context).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
    }
    private val primaryColor = Color.parseColor("#75FFF1")
    private val lightBackgroundColor = Color.parseColor("#181A1E")

    private val bottomButton: Button by lazy {
        Button(context).apply {
            text = "Preview"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#203133"))
            setTextColor(primaryColor)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            val image = ContextCompat.getDrawable(context, R.drawable.play_button)?.apply {
                setTint(primaryColor)
            }
            setCompoundDrawablesWithIntrinsicBounds(image, null, null, null)
            compoundDrawablePadding = 8 // or whatever space you need
            setPadding(0, -1, 0, 0)

            setOnClickListener { pressedBottomButton() }
        }
    }

    private val previewPickerButton: Button by lazy {
        Button(context).apply {
            text = ""
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(lightBackgroundColor)
            setTextColor(primaryColor)
            setPadding(0, 0, 10, 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            val image = ContextCompat.getDrawable(context, R.drawable.down_arrow)?.apply {
                setTint(primaryColor)
            }
            setCompoundDrawablesWithIntrinsicBounds(null, null, image, null)
            compoundDrawablePadding = 8 // or whatever space you need

            setOnClickListener { pressedPreview() }
        }
    }


    init {
        initialLocaleIdentifier = Superwall.instance.options.localeIdentifier
        addSubviews()
        CoroutineScope(Dispatchers.IO).launch {
            loadPreview()
        }
    }

    fun viewDidDisappear() {
        paywallManager.resetCache()
        //debugManager.isDebuggerLaunched = false
        Superwall.instance.options.localeIdentifier = initialLocaleIdentifier
    }

    fun addSubviews() {
        val layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)

        // Adding views to the layout
        addView(activityIndicator, layoutParams)
        addView(logoImageView, layoutParams)
        addView(consoleButton, layoutParams)
        addView(exitButton, layoutParams)
        addView(bottomButton, layoutParams)
        addView(previewPickerButton, layoutParams)
        // TODO: Add previewContainerView back in

        // Setting background color
        setBackgroundColor(lightBackgroundColor)

        // Setting up the constraints
        val constraintSet = ConstraintSet()
        constraintSet.clone(this)

        // TODO: Change to previewContainerView
        constraintSet.connect(previewPickerButton.id, ConstraintSet.START, id, ConstraintSet.START)
        constraintSet.connect(previewPickerButton.id, ConstraintSet.END, id, ConstraintSet.END)
        constraintSet.connect(previewPickerButton.id, ConstraintSet.TOP, logoImageView.id, ConstraintSet.BOTTOM, 25)
        constraintSet.connect(previewPickerButton.id, ConstraintSet.BOTTOM, bottomButton.id, ConstraintSet.TOP, -30)

        constraintSet.constrainWidth(logoImageView.id, ConstraintSet.WRAP_CONTENT)
        constraintSet.constrainHeight(logoImageView.id, 20)
        constraintSet.connect(logoImageView.id, ConstraintSet.TOP, id, ConstraintSet.TOP, 20)
        constraintSet.connect(logoImageView.id, ConstraintSet.END, id, ConstraintSet.END)
        constraintSet.connect(logoImageView.id, ConstraintSet.START, id, ConstraintSet.START)

        constraintSet.connect(consoleButton.id, ConstraintSet.END, bottomButton.id, ConstraintSet.START)
        constraintSet.connect(consoleButton.id, ConstraintSet.TOP, logoImageView.id, ConstraintSet.TOP)

        constraintSet.connect(exitButton.id, ConstraintSet.START, bottomButton.id, ConstraintSet.END)
        constraintSet.connect(exitButton.id, ConstraintSet.TOP, logoImageView.id, ConstraintSet.TOP)

        // TODO: Change to previewContainerView
        constraintSet.connect(activityIndicator.id, ConstraintSet.TOP, previewPickerButton.id, ConstraintSet.TOP)
        constraintSet.connect(activityIndicator.id, ConstraintSet.BOTTOM, previewPickerButton.id, ConstraintSet.BOTTOM)
        constraintSet.connect(activityIndicator.id, ConstraintSet.START, previewPickerButton.id, ConstraintSet.START)
        constraintSet.connect(activityIndicator.id, ConstraintSet.END, previewPickerButton.id, ConstraintSet.END)

        constraintSet.connect(bottomButton.id, ConstraintSet.START, id, ConstraintSet.START, 20)
        constraintSet.connect(bottomButton.id, ConstraintSet.END, id, ConstraintSet.END, 20)
        constraintSet.connect(bottomButton.id, ConstraintSet.BOTTOM, id, ConstraintSet.BOTTOM, 10)
        constraintSet.constrainHeight(bottomButton.id, 64)


        // TODO: Change second one to previewContainerView
        constraintSet.connect(previewPickerButton.id, ConstraintSet.START, previewPickerButton.id, ConstraintSet.START)
        constraintSet.connect(previewPickerButton.id, ConstraintSet.END, previewPickerButton.id, ConstraintSet.END)
        constraintSet.connect(previewPickerButton.id, ConstraintSet.TOP, previewPickerButton.id, ConstraintSet.BOTTOM)
        constraintSet.constrainHeight(previewPickerButton.id, 26)

        constraintSet.applyTo(this)
    }

    suspend fun loadPreview() {
        activityIndicator.visibility = View.VISIBLE
        previewViewContent?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }

        if (paywalls.isEmpty()) {
            try {
                paywalls = network.getPaywalls()
                finishLoadingPreview()
            } catch (error: Exception) {
                Logger.debug(
                    logLevel = LogLevel.error,
                    scope = LogScope.debugViewController,
                    message = "Failed to Fetch Paywalls",
                    error = error
                )
            }
        } else {
            finishLoadingPreview()
        }
    }

    suspend fun finishLoadingPreview() {
        var paywallId: String? = paywallIdentifier ?: paywallDatabaseId?.let { dbId ->
            paywalls.firstOrNull { it.databaseId == dbId }?.identifier?.also { identifier ->
                paywallIdentifier = identifier
            }
        } ?: return

        try {
            val request = factory.makePaywallRequest(
                eventData = null,
                responseIdentifiers = ResponseIdentifiers(paywallId),
                overrides = null,
                isDebuggerLaunched = true,
                presentationSourceType = null,
                retryCount = 6
            )
            var paywall = paywallRequestManager.getPaywall(request)

            val productVariables = storeKitManager.getProductVariables(paywall)
            paywall.productVariables = productVariables

            this.paywall = paywall
            previewPickerButton.text = paywall.name
            activityIndicator.stopAnimating()
            addPaywallPreview()
        } catch (error: Exception) {
            Logger.debug(
                logLevel = LogLevel.error,
                scope = LogScope.debugViewController,
                message = "No Paywall Response",
                error = error
            )
        }
    }

    suspend fun addPaywallPreview() {
        // TODO: Change this
        val paywall = paywall ?: return

        val child = factory.makePaywallViewController(
            paywall = paywall,
            cache = null,
            delegate = null
        )
        supportFragmentManager.beginTransaction().add(child, "PaywallFragment").commit()

        previewContainerView.addView(child.view)
        previewViewContent = child.view

        child.view.apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isUserInteractionEnabled = false
        }

        val constraints = ConstraintSet().apply {
            clone(previewContainerView)
            connect(child.view.id, ConstraintSet.START, previewContainerView.id, ConstraintSet.START)
            connect(child.view.id, ConstraintSet.END, previewContainerView.id, ConstraintSet.END)
            connect(child.view.id, ConstraintSet.TOP, previewContainerView.id, ConstraintSet.TOP)
            connect(child.view.id, ConstraintSet.BOTTOM, previewContainerView.id, ConstraintSet.BOTTOM)
        }
        constraints.applyTo(previewContainerView)

        child.view.apply {
            clipToOutline = true
            background = ColorDrawable(Color.WHITE)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View?, outline: Outline?) {
                    outline?.setRoundRect(0, 0, view!!.width, view.height, 52f)
                }
            }
            alpha = 0.0f
        }

        val ratio = previewContainerView.height.toFloat() / rootView.height.toFloat()

        child.view.apply {
            scaleX = ratio
            scaleY = ratio
        }

        child.view.animate()
            .alpha(1.0f)
            .setDuration(250)
            .setStartDelay(100)
            .start()
    }

    fun pressedExitButton() {
        CoroutineScope(Dispatchers.IO).launch {
          //  debugManager.closeDebugger(animated = false)
        }
    }

    fun pressedConsoleButton() {
        var releaseVersionNumber = ""
        var versionCode = ""
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            releaseVersionNumber = packageInfo.versionName
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toString()
            } else {
                packageInfo.versionCode.toLong().toString()
            }
        } catch (e: PackageManager.NameNotFoundException) {}
        val version = BuildConfig.SDK_VERSION

        val message = "Superwall v$version | App v$releaseVersionNumber ($versionCode)"

        // Assuming you have a showOptionsAlert function
        showOptionsAlert(
            title = null,
            message = message,
            options = listOf(
                AlertOption("Localization", ::showLocalizationPicker),
                AlertOption("Templates", ::showConsole)
            ),
            anchor = consoleButton
        )
    }

    fun pressedBottomButton() {
        val alertOptions = listOf(
            AlertOption(
                title = "With Free Trial",
                action = { loadAndShowPaywall(true) },
                style = AlertDialog.BUTTON_POSITIVE
            ),
            AlertOption(
                title = "Without Free Trial",
                action = { loadAndShowPaywall(false) },
                style = AlertDialog.BUTTON_POSITIVE
            )
        )
        presentAlert("Which version?", null, alertOptions)
    }

    fun presentAlert(
        title: String?,
        message: String?,
        options: List<AlertOption> = emptyList(),
        sourceView: View
    ) {
        val builder = AlertDialog.Builder(this) // 'this' should be the Context, adjust as necessary
        builder.setTitle(title)
        builder.setMessage(message)

        val items = options.map { it.title }.toTypedArray()
        builder.setItems(items) { _, which ->
            val option = options[which]
            CoroutineScope(Dispatchers.IO).launch {
                option.action?.invoke()
            }
        }

        val dialog = builder.create()

        // Setting up for showing as a bottom sheet if possible
        dialog.window?.attributes?.gravity = Gravity.BOTTOM
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        dialog.show()
        dialog.window?.findViewById<View>(androidx.appcompat.R.id.contentPanel)?.post {
            val contentPanel = dialog.window?.findViewById<View>(androidx.appcompat.R.id.contentPanel)
            // remove dialog's default background to make it look more like an action sheet
            contentPanel?.background = null
        }
    }

    fun loadAndShowPaywall(freeTrialAvailable: Boolean = false) {
        val paywallIdentifier = paywallIdentifier ?: return

       // bottomButton.setImageDrawable(null)
       // bottomButton.showLoading = true

        val inactiveSubscriptionPublisher = MutableStateFlow(SubscriptionStatus.INACTIVE)

        val presentationRequest = factory.makePresentationRequest(
            PresentationInfo.FromIdentifier(
                paywallIdentifier,
                freeTrialOverride = freeTrialAvailable
            ),
            paywallOverrides = null,
            presenter = encapsulatingActivity,
            isDebuggerLaunched = true,
            subscriptionStatus =  inactiveSubscriptionPublisher,
            isPaywallPresented = Superwall.instance.isPaywallPresented,
            type = PresentationRequestType.Presentation
        )

        val publisher = MutableSharedFlow<PaywallState>()

        job = launch(Dispatchers.Main) {
            publisher.collect { state ->
                when (state) {
                    is PaywallState.Presented -> {
                       // bottomButton.showLoading = false
                        val playButton = ResourcesCompat.getDrawable(resources, R.drawable.play_button, null)
                        //bottomButton.setImageDrawable(playButton)
                    }
                    is PaywallState.Skipped -> {
                        val errorMessage = when (state.paywallSkippedReason) {
                            is PaywallSkippedReason.Holdout -> "The user was assigned to a holdout."
                            is PaywallSkippedReason.NoRuleMatch -> "The user didn't match a rule."
                            is PaywallSkippedReason.EventNotFound -> "Couldn't find event."
                            is PaywallSkippedReason.UserIsSubscribed -> "The user is subscribed."
                        }
                        presentAlert(
                            title = "Paywall Skipped",
                            message = errorMessage,
                            sourceView = this@DebugViewController
                        )
                       // bottomButton.showLoading = false
                        val playButton = ResourcesCompat.getDrawable(resources, R.drawable.play_button, null)
                     //   bottomButton.setImageDrawable(playButton)
                    }
                    is PaywallState.Dismissed -> {
                        // Handle dismissed state if needed
                    }
                    is PaywallState.PresentationError -> {
                        Logger.debug(
                            logLevel = LogLevel.error,
                            scope = LogScope.debugViewController,
                            message = "Failed to Show Paywall"
                        )
                        presentAlert(
                            title = "Presentation Error",
                            message = state.error.localizedMessage,
                            sourceView = this@DebugViewController
                        )
                       // bottomButton.showLoading = false
                        val playButton = ResourcesCompat.getDrawable(resources, R.drawable.play_button, null)
                        //bottomButton.setImageDrawable(playButton)
                    }
                }
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            Superwall.instance.internallyPresent(presentationRequest, publisher)
        }
    }
}

internal class DebugViewControllerActivity : Activity() {
    companion object {
        private const val VIEW_KEY = "debugViewKey"

        fun startWithView(
            context: Context,
            view: View
        ) {
            val key = UUID.randomUUID().toString()
            ViewStorage.storeView(key, view)

            val intent = Intent(context, DebugViewControllerActivity::class.java).apply {
                putExtra(VIEW_KEY, key)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

            context.startActivity(intent)
        }
    }

    private var contentView: View? = null

    override fun setContentView(view: View) {
        super.setContentView(view)
        contentView = view
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val key = intent.getStringExtra(VIEW_KEY)
        if (key == null) {
            finish() // Close the activity if there's no key
            return
        }

        val view = ViewStorage.retrieveView(key) ?: run {
            finish() // Close the activity if the view associated with the key is not found
            return
        }

        if (view is ActivityEncapsulatable) {
            view.encapsulatingActivity = this
        }

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // Now add
        setContentView(view)
    }

    override fun onStop() {
        super.onStop()

        val debugViewController = contentView as? DebugViewController ?: return

        CoroutineScope(Dispatchers.IO).launch {
            debugViewController.viewDidDisappear()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Clear reference to activity in the view
        (contentView as? ActivityEncapsulatable)?.encapsulatingActivity = null

        // Clear the reference to the contentView
        contentView = null
    }
}