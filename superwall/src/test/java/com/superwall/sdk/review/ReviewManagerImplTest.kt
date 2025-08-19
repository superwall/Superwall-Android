package com.superwall.sdk.review

import android.app.Activity
import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.play.core.review.ReviewException
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManagerFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.google.android.play.core.review.ReviewManager as PlayReviewManager

class ReviewManagerImplTest {
    private lateinit var context: Context
    private lateinit var activity: Activity
    private lateinit var playReviewManager: PlayReviewManager
    private lateinit var reviewManagerImpl: ReviewManagerImpl

    @Before
    fun setUp() {
        context = mockk()
        activity = mockk()
        playReviewManager = mockk()

        mockkStatic(ReviewManagerFactory::class)
        every { ReviewManagerFactory.create(context) } returns playReviewManager

        reviewManagerImpl = ReviewManagerImpl(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `requestReviewFlow returns ReviewInfo when play review manager succeeds`() =
        runTest {
            // Given
            val mockReviewInfo = mockk<ReviewInfo>()
            val mockTask = mockk<Task<ReviewInfo>>()
            val callbackSlot = slot<(Task<ReviewInfo>) -> Unit>()

            every { playReviewManager.requestReviewFlow() } returns mockTask
            every { mockTask.addOnCompleteListener(capture(callbackSlot)) } answers {
                // Simulate successful task completion
                val task = mockk<Task<ReviewInfo>>()
                every { task.isSuccessful } returns true
                every { task.result } returns mockReviewInfo
                callbackSlot.captured.invoke(task)
                mockTask
            }

            // When
            val result = reviewManagerImpl.requestReviewFlow()

            // Then
            assertEquals(mockReviewInfo, result)
        }

    @Test
    fun `requestReviewFlow throws RequestFlowError when play review manager fails with ReviewException`() =
        runTest {
            // Given
            val errorCode = 123
            val reviewException = ReviewException(errorCode)
            val mockTask = mockk<Task<ReviewInfo>>()
            val callbackSlot = slot<(Task<ReviewInfo>) -> Unit>()

            every { playReviewManager.requestReviewFlow() } returns mockTask
            every { mockTask.addOnCompleteListener(capture(callbackSlot)) } answers {
                // Simulate failed task completion
                val task = mockk<Task<ReviewInfo>>()
                every { task.isSuccessful } returns false
                every { task.exception } returns reviewException
                callbackSlot.captured.invoke(task)
                mockTask
            }

            // When & Then
            try {
                reviewManagerImpl.requestReviewFlow()
                assertTrue("Expected ReviewError.RequestFlowError to be thrown", false)
            } catch (error: ReviewError.RequestFlowError) {
                assertEquals(errorCode, error.errorCode)
            }
        }

    @Test
    fun `requestReviewFlow throws GenericError when play review manager fails with generic exception`() =
        runTest {
            // Given
            val genericException = RuntimeException("Generic error")
            val mockTask = mockk<Task<ReviewInfo>>()
            val callbackSlot = slot<(Task<ReviewInfo>) -> Unit>()

            every { playReviewManager.requestReviewFlow() } returns mockTask
            every { mockTask.addOnCompleteListener(capture(callbackSlot)) } answers {
                // Simulate failed task completion
                val task = mockk<Task<ReviewInfo>>()
                every { task.isSuccessful } returns false
                every { task.exception } returns genericException
                callbackSlot.captured.invoke(task)
                mockTask
            }

            // When & Then
            try {
                reviewManagerImpl.requestReviewFlow()
                assertTrue("Expected ReviewError.GenericError to be thrown", false)
            } catch (error: ReviewError.GenericError) {
                assertTrue(error.message!!.contains("Generic error"))
            }
        }

    @Test
    fun `launchReviewFlow completes successfully when play review manager succeeds`() =
        runTest {
            // Given
            val mockReviewInfo = mockk<ReviewInfo>()
            val mockTask = mockk<Task<Void>>()
            val callbackSlot = slot<(Task<Void>) -> Unit>()

            every { playReviewManager.launchReviewFlow(activity, mockReviewInfo) } returns mockTask
            every { mockTask.addOnCompleteListener(capture(callbackSlot)) } answers {
                // Simulate successful task completion
                val task = mockk<Task<Void>>()
                every { task.isSuccessful } returns true
                callbackSlot.captured.invoke(task)
                mockTask
            }

            // When
            reviewManagerImpl.launchReviewFlow(activity, mockReviewInfo)

            // Then - no exception should be thrown
        }

    @Test
    fun `launchReviewFlow throws LaunchFlowError when play review manager fails`() =
        runTest {
            // Given
            val mockReviewInfo = mockk<ReviewInfo>()
            val genericException = RuntimeException("Launch failed")
            val mockTask = mockk<Task<Void>>()
            val callbackSlot = slot<(Task<Void>) -> Unit>()

            every { playReviewManager.launchReviewFlow(activity, mockReviewInfo) } returns mockTask
            every { mockTask.addOnCompleteListener(capture(callbackSlot)) } answers {
                // Simulate failed task completion
                val task = mockk<Task<Void>>()
                every { task.isSuccessful } returns false
                every { task.exception } returns genericException
                callbackSlot.captured.invoke(task)
                mockTask
            }

            // When & Then
            try {
                reviewManagerImpl.launchReviewFlow(activity, mockReviewInfo)
                assertTrue("Expected ReviewError.LaunchFlowError to be thrown", false)
            } catch (error: ReviewError.LaunchFlowError) {
                assertEquals(genericException, error.cause)
            }
        }
}
